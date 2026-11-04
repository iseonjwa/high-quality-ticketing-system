package com.iseonjwa.reservation.reservation.presentation;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// 예매 상세·내 예매 목록 조회(ISE-171). 공연·회차·좌석·예매·티켓은 JDBC로 직접 넣는다.
// 사용자 ID는 테스트마다 새로 뽑아 다른 테스트가 넣은 예매와 섞이지 않게 한다
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.config.import=", "spring.cloud.config.enabled=false", "eureka.client.enabled=false"})
@ActiveProfiles("test")
class ReservationApiTest {
    private static final String MARK = "ISE-171 예매 조회 테스트";
    private static final Instant STARTS_AT = Instant.parse("2030-05-01T10:00:00Z");

    private final HttpClient http = HttpClient.newHttpClient();
    private final long userId = ThreadLocalRandom.current().nextLong(1_000_000_000L, 2_000_000_000L);

    @LocalServerPort
    int port;
    @Autowired
    JdbcTemplate jdbc;

    private Long venueId;
    private Long scheduleId;
    private Long seatA;
    private Long seatB;

    @BeforeEach
    void setUp() {
        venueId = jdbc.queryForObject("INSERT INTO venues (name, created_at, updated_at)"
                + " VALUES (?, now(), now()) RETURNING id", Long.class, MARK);
        var concertId = jdbc.queryForObject("INSERT INTO concerts (title, version, created_at, updated_at)"
                + " VALUES (?, 0, now(), now()) RETURNING id", Long.class, MARK);
        scheduleId = jdbc.queryForObject("INSERT INTO schedules (concert_id, venue_id, starts_at, open_at, version,"
                + " created_at, updated_at) VALUES (?, ?, ?, now() - interval '1 minute', 0, now(), now())"
                + " RETURNING id", Long.class, concertId, venueId, Timestamp.from(STARTS_AT));
        seatA = scheduleSeat("A", 1, 7, "VIP", 165000);
        seatB = scheduleSeat("A", 1, 8, "VIP", 165000);
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM tickets WHERE reservation_seat_id IN (SELECT rs.id FROM reservation_seats rs"
                + " JOIN reservations r ON r.id = rs.reservation_id WHERE r.schedule_id = ?)", scheduleId);
        jdbc.update("DELETE FROM reservation_seats WHERE schedule_seat_id IN (?, ?)", seatA, seatB);
        jdbc.update("DELETE FROM reservations WHERE schedule_id = ?", scheduleId);
        jdbc.update("DELETE FROM schedule_seats WHERE schedule_id = ?", scheduleId);
        jdbc.update("DELETE FROM schedules WHERE venue_id = ?", venueId);
        jdbc.update("DELETE FROM venue_seats WHERE venue_id = ?", venueId);
        jdbc.update("DELETE FROM concerts WHERE title = ?", MARK);
        jdbc.update("DELETE FROM venues WHERE id = ?", venueId);
    }

    @Test
    void 선점_중인_본인_예매는_좌석과_결제_토큰을_주고_티켓은_비운다() throws Exception {
        var reservationId = reservation(userId, "HELD", "now() + interval '5 minutes'", "now()");
        reservationSeat(reservationId, seatA, "HELD");
        reservationSeat(reservationId, seatB, "HELD");

        var response = get("/api/v1/reservations/" + reservationId, userId);

        assertEquals(200, response.status());
        var data = response.json().get("data");
        assertEquals(reservationId.longValue(), data.get("reservationId").asLong());
        assertEquals(scheduleId.longValue(), data.get("scheduleId").asLong());
        assertEquals("HELD", data.get("status").asString());
        assertEquals(330000L, data.get("totalAmount").asLong());
        assertTrue(data.get("holdExpiresAt").asString().endsWith("+09:00"));
        var seats = data.get("seats");
        assertEquals(2, seats.size());
        var first = seats.get(0);
        assertEquals(seatA.longValue(), first.get("seatId").asLong());
        assertEquals("A", first.get("section").asString());
        assertEquals(1, first.get("row").asInt());
        assertEquals(7, first.get("number").asInt());
        assertEquals("VIP", first.get("grade").asString());
        assertEquals(165000L, first.get("price").asLong());
        assertFalse(data.get("paymentToken").isNull());
        assertEquals(0, data.get("tickets").size());
    }

    @Test
    void 선점_만료_10초_전부터는_결제_토큰을_주지_않는다() throws Exception {
        var reservationId = reservation(userId, "HELD", "now() + interval '5 seconds'", "now()");
        reservationSeat(reservationId, seatA, "HELD");

        var data = get("/api/v1/reservations/" + reservationId, userId).json().get("data");

        assertEquals("HELD", data.get("status").asString());
        assertTrue(data.get("paymentToken").isNull());
    }

    @Test
    void 확정된_예매는_좌석마다_티켓을_주고_결제_토큰은_주지_않는다() throws Exception {
        var reservationId = reservation(userId, "CONFIRMED", "now() + interval '5 minutes'", "now()");
        ticket(reservationSeat(reservationId, seatA, "CONFIRMED"), "T-ISE171-A");
        ticket(reservationSeat(reservationId, seatB, "CONFIRMED"), "T-ISE171-B");

        var data = get("/api/v1/reservations/" + reservationId, userId).json().get("data");

        assertEquals("CONFIRMED", data.get("status").asString());
        assertTrue(data.get("paymentToken").isNull());
        var tickets = data.get("tickets");
        assertEquals(2, tickets.size());
        assertEquals("T-ISE171-A", tickets.get(0).get("ticketNo").asString());
        assertEquals(seatA.longValue(), tickets.get(0).get("seatId").asLong());
        assertEquals(seatB.longValue(), tickets.get(1).get("seatId").asLong());
    }

    @Test
    void 다른_사용자의_예매와_없는_예매는_404다() throws Exception {
        var reservationId = reservation(userId, "HELD", "now() + interval '5 minutes'", "now()");

        assertEquals(404, get("/api/v1/reservations/" + reservationId, userId + 1).status());
        assertEquals(404, get("/api/v1/reservations/999999999", userId).status());
    }

    @Test
    void ID가_0_이하면_400이고_로그인하지_않으면_401이다() throws Exception {
        assertEquals(400, get("/api/v1/reservations/0", userId).status());
        assertEquals(401, get("/api/v1/reservations/1", null).status());
        assertEquals(401, get("/api/v1/reservations/me", null).status());
    }

    @Test
    void 내_예매_목록은_최근_예매부터_공연_제목과_시작_시각을_붙여_준다() throws Exception {
        var older = reservation(userId, "CONFIRMED", "now() + interval '5 minutes'", "now() - interval '1 hour'");
        var newer = reservation(userId, "HELD", "now() + interval '5 minutes'", "now()");
        reservation(userId + 1, "HELD", "now() + interval '5 minutes'", "now()");

        var response = get("/api/v1/reservations/me", userId);

        assertEquals(200, response.status());
        var data = response.json().get("data");
        assertEquals(2L, data.get("totalElements").asLong());
        var content = data.get("content");
        assertEquals(List.of(newer, older), List.of(content.get(0).get("reservationId").asLong(),
                content.get(1).get("reservationId").asLong()));
        var first = content.get(0);
        assertEquals(MARK, first.get("concertTitle").asString());
        assertEquals(STARTS_AT, OffsetDateTime.parse(first.get("startsAt").asString()).toInstant());
        assertTrue(first.get("startsAt").asString().endsWith("+09:00"));
        assertEquals("HELD", first.get("status").asString());
        assertEquals(330000L, first.get("totalAmount").asLong());
        assertFalse(first.has("paymentToken"));
    }

    @Test
    void 내_예매_목록은_쪽_단위로_나눠_준다() throws Exception {
        reservation(userId, "HELD", "now() + interval '5 minutes'", "now() - interval '2 hours'");
        reservation(userId, "HELD", "now() + interval '5 minutes'", "now() - interval '1 hour'");
        var newest = reservation(userId, "HELD", "now() + interval '5 minutes'", "now()");

        var data = get("/api/v1/reservations/me?page=0&size=2", userId).json().get("data");
        var last = get("/api/v1/reservations/me?page=1&size=2", userId).json().get("data");

        assertEquals(2, data.get("content").size());
        assertEquals(newest.longValue(), data.get("content").get(0).get("reservationId").asLong());
        assertEquals(3L, data.get("totalElements").asLong());
        assertEquals(1, last.get("content").size());
        assertEquals(400, get("/api/v1/reservations/me?size=101", userId).status());
    }

    private Long scheduleSeat(String section, int rowNo, int seatNo, String grade, long price) {
        var venueSeatId = jdbc.queryForObject("INSERT INTO venue_seats (venue_id, section, row_no, seat_no, grade,"
                + " created_at, updated_at) VALUES (?, ?, ?, ?, ?, now(), now()) RETURNING id", Long.class,
                venueId, section, rowNo, seatNo, grade);
        return jdbc.queryForObject("INSERT INTO schedule_seats (schedule_id, venue_seat_id, section, grade, price,"
                + " status, created_at, updated_at) VALUES (?, ?, ?, ?, ?, 'HELD', now(), now()) RETURNING id",
                Long.class, scheduleId, venueSeatId, section, grade, price);
    }

    // holdExpiresAt·createdAt은 SQL 식으로 받는다. 선점 만료 판단이 DB 시각과 서버 시각에 같이 맞게 now() 기준으로 넣는다
    private Long reservation(long ownerId, String status, String holdExpiresAt, String createdAt) {
        return jdbc.queryForObject("INSERT INTO reservations (reservation_no, request_id, user_id, schedule_id, status,"
                + " quantity, total_amount, hold_expires_at, created_at, updated_at)"
                + " VALUES (substr(md5(random()::text), 1, 20), gen_random_uuid(), ?, ?, ?, 2, 330000, "
                + holdExpiresAt + ", " + createdAt + ", now()) RETURNING id", Long.class, ownerId, scheduleId, status);
    }

    private Long reservationSeat(Long reservationId, Long scheduleSeatId, String status) {
        return jdbc.queryForObject("INSERT INTO reservation_seats (reservation_id, schedule_seat_id, price, status,"
                + " created_at, updated_at) VALUES (?, ?, 165000, ?, now(), now()) RETURNING id", Long.class,
                reservationId, scheduleSeatId, status);
    }

    private void ticket(Long reservationSeatId, String ticketNo) {
        jdbc.update("INSERT INTO tickets (ticket_no, reservation_seat_id, status, created_at, updated_at)"
                + " VALUES (?, ?, 'ISSUED', now(), now())", ticketNo, reservationSeatId);
    }

    private Response get(String path, Long requester) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET();
        if (requester != null) {
            builder.header("X-User-Id", requester.toString()).header("X-User-Role", "USER");
        }
        var response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        return new Response(response.statusCode(), response.body());
    }

    private record Response(int status, String body) {
        JsonNode json() {
            return JsonMapper.builder().build().readTree(body);
        }
    }
}
