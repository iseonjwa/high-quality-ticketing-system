package com.iseonjwa.reservation.reservation.presentation;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.dao.DataIntegrityViolationException;
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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// 예매 취소(ISE-206). 공연·회차·좌석·예매·티켓·매수 행은 JDBC로 직접 넣고, 사용자 ID는 테스트마다 새로 뽑는다
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.config.import=", "spring.cloud.config.enabled=false", "eureka.client.enabled=false"})
@ActiveProfiles("test")
class ReservationCancelApiTest {
    private static final String MARK = "ISE-206 예매 취소 테스트";
    private static final Instant FUTURE_STARTS_AT = Instant.parse("2030-05-01T10:00:00Z");
    private static final Instant PAST_STARTS_AT = Instant.parse("2020-05-01T10:00:00Z");
    private static final long PAYMENT_ID = 7_001L;

    private final HttpClient http = HttpClient.newHttpClient();
    private final long userId = ThreadLocalRandom.current().nextLong(1_000_000_000L, 2_000_000_000L);

    @LocalServerPort
    int port;
    @Autowired
    JdbcTemplate jdbc;

    private Long venueId;
    private Long concertId;
    private Long scheduleId;
    private Long seatA;
    private Long seatB;

    @BeforeEach
    void setUp() {
        venueId = jdbc.queryForObject("INSERT INTO venues (name, created_at, updated_at)"
                + " VALUES (?, now(), now()) RETURNING id", Long.class, MARK);
        concertId = jdbc.queryForObject("INSERT INTO concerts (title, version, created_at, updated_at)"
                + " VALUES (?, 0, now(), now()) RETURNING id", Long.class, MARK);
        scheduleId = schedule(FUTURE_STARTS_AT);
        seatA = scheduleSeat(scheduleId, 7, "SOLD");
        seatB = scheduleSeat(scheduleId, 8, "SOLD");
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM outbox_events WHERE topic = 'payment.command' AND message_key IN"
                + " (SELECT id::text FROM reservations WHERE user_id = ?)", userId);
        jdbc.update("DELETE FROM tickets WHERE reservation_seat_id IN (SELECT rs.id FROM reservation_seats rs"
                + " JOIN reservations r ON r.id = rs.reservation_id WHERE r.user_id = ?)", userId);
        jdbc.update("DELETE FROM reservation_status_histories WHERE reservation_id IN"
                + " (SELECT id FROM reservations WHERE user_id = ?)", userId);
        jdbc.update("DELETE FROM reservation_seats WHERE reservation_id IN"
                + " (SELECT id FROM reservations WHERE user_id = ?)", userId);
        jdbc.update("DELETE FROM reservations WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM user_schedule_quotas WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM schedule_seats WHERE schedule_id IN (SELECT id FROM schedules WHERE venue_id = ?)",
                venueId);
        jdbc.update("DELETE FROM schedules WHERE venue_id = ?", venueId);
        jdbc.update("DELETE FROM venue_seats WHERE venue_id = ?", venueId);
        jdbc.update("DELETE FROM concerts WHERE title = ?", MARK);
        jdbc.update("DELETE FROM venues WHERE id = ?", venueId);
    }

    @Test
    void 확정_예매를_취소하면_좌석과_티켓을_돌려주고_환불을_요청한다() throws Exception {
        var reservationId = confirmedReservation(userId, "GENERAL");
        var before = updatedAt(reservationId);

        var response = cancel(reservationId, userId, UUID.randomUUID().toString());

        assertEquals(202, response.status());
        var data = response.json().get("data");
        assertEquals(reservationId.longValue(), data.get("reservationId").asLong());
        assertEquals("CANCELED", data.get("status").asString());
        assertEquals(PAYMENT_ID, data.get("paymentId").asLong());
        assertEquals("CANCELED", reservationStatus(reservationId));
        assertTrue(updatedAt(reservationId).isAfter(before));
        assertEquals(List.of("RELEASED", "RELEASED"), reservationSeatStatuses(reservationId));
        assertEquals(List.of("AVAILABLE", "AVAILABLE"), scheduleSeatStatuses());
        assertEquals(List.of("VOID", "VOID"), ticketStatuses(reservationId));
        assertEquals(2, heldCount());
        assertEquals(1, historyCount(reservationId));
        assertEquals(1, refundCount(reservationId));
        var payload = jdbc.queryForMap("SELECT payload->'payload'->>'reason' AS reason,"
                + " payload->'payload'->>'reservationId' AS reservation_id,"
                + " payload->'payload'->>'paymentId' AS payment_id, payload->'payload'->>'userId' AS user_id,"
                + " payload->'payload'->>'amount' AS amount, payload->'payload'->>'currency' AS currency"
                + " FROM outbox_events WHERE topic = 'payment.command' AND event_type = 'RefundRequested'"
                + " AND message_key = ?", String.valueOf(reservationId));
        assertEquals("CANCELED", payload.get("reason"));
        assertEquals(String.valueOf(reservationId), payload.get("reservation_id"));
        assertEquals(String.valueOf(PAYMENT_ID), payload.get("payment_id"));
        assertEquals(String.valueOf(userId), payload.get("user_id"));
        assertEquals("330000", payload.get("amount"));
        assertEquals("KRW", payload.get("currency"));
    }

    @Test
    void 같은_요청을_다시_보내도_202이고_환불_요청은_한_건이다() throws Exception {
        var reservationId = confirmedReservation(userId, "GENERAL");
        var key = UUID.randomUUID().toString();

        assertEquals(202, cancel(reservationId, userId, key).status());
        var sameKey = cancel(reservationId, userId, key);
        var otherKey = cancel(reservationId, userId, UUID.randomUUID().toString());

        assertEquals(202, sameKey.status());
        assertEquals("CANCELED", sameKey.json().get("data").get("status").asString());
        assertEquals(202, otherKey.status());
        assertEquals(1, refundCount(reservationId));
        assertEquals(1, historyCount(reservationId));
        assertEquals(2, heldCount());
    }

    @Test
    void 동시에_여러_번_취소해도_모두_202이고_환불_요청은_한_건이다() throws Exception {
        var reservationId = confirmedReservation(userId, "GENERAL");
        var threads = 8;
        var ready = new CountDownLatch(threads);
        var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(threads);
        try {
            var futures = new ArrayList<Future<Response>>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    return cancel(reservationId, userId, UUID.randomUUID().toString());
                }));
            }
            ready.await();
            start.countDown();
            for (var future : futures) {
                var response = future.get(30, TimeUnit.SECONDS);
                assertEquals(202, response.status(), response.body());
            }
        } finally {
            pool.shutdownNow();
        }

        assertEquals("CANCELED", reservationStatus(reservationId));
        assertEquals(1, refundCount(reservationId));
        assertEquals(1, historyCount(reservationId));
        assertEquals(2, heldCount());
        assertEquals(List.of("AVAILABLE", "AVAILABLE"), scheduleSeatStatuses());
    }

    @Test
    void 다른_사용자의_예매는_404이고_아무것도_바뀌지_않는다() throws Exception {
        var reservationId = confirmedReservation(userId, "GENERAL");

        var response = cancel(reservationId, userId + 1, UUID.randomUUID().toString());

        assertEquals(404, response.status());
        assertEquals("NOT_FOUND", response.json().get("code").asString());
        assertEquals("CONFIRMED", reservationStatus(reservationId));
        assertEquals(List.of("SOLD", "SOLD"), scheduleSeatStatuses());
        assertEquals(List.of("ISSUED", "ISSUED"), ticketStatuses(reservationId));
        assertEquals(4, heldCount());
        assertEquals(0, refundCount(reservationId));
        assertEquals(404, cancel(999_999_999L, userId, UUID.randomUUID().toString()).status());
    }

    @Test
    void 선점_중인_예매는_409다() throws Exception {
        var reservationId = reservation(userId, "HELD", "GENERAL", scheduleId);

        var response = cancel(reservationId, userId, UUID.randomUUID().toString());

        assertEquals(409, response.status());
        assertEquals("RESERVATION_NOT_CANCELABLE", response.json().get("code").asString());
        assertEquals("HELD", reservationStatus(reservationId));
        assertEquals(0, refundCount(reservationId));
    }

    @Test
    void 공연이_시작한_회차의_확정_예매는_409다() throws Exception {
        var pastScheduleId = schedule(PAST_STARTS_AT);
        var pastSeat = scheduleSeat(pastScheduleId, 9, "SOLD");
        var reservationId = reservation(userId, "CONFIRMED", "GENERAL", pastScheduleId);
        var reservationSeatId = reservationSeat(reservationId, pastSeat, "CONFIRMED");
        ticket(reservationSeatId, "T-ISE206-P");

        var response = cancel(reservationId, userId, UUID.randomUUID().toString());

        assertEquals(409, response.status());
        assertEquals("CANCEL_PERIOD_ENDED", response.json().get("code").asString());
        assertEquals("CONFIRMED", reservationStatus(reservationId));
        assertEquals(0, refundCount(reservationId));
    }

    @Test
    void 이미_취소한_예매는_공연이_시작한_뒤에_다시_보내도_202이고_아무것도_쓰지_않는다() throws Exception {
        var pastScheduleId = schedule(PAST_STARTS_AT);
        var pastSeat = scheduleSeat(pastScheduleId, 9, "AVAILABLE");
        var reservationId = reservation(userId, "CANCELED", "GENERAL", pastScheduleId);
        reservationSeat(reservationId, pastSeat, "RELEASED");
        var before = updatedAt(reservationId);

        var response = cancel(reservationId, userId, UUID.randomUUID().toString());

        assertEquals(202, response.status());
        assertEquals("CANCELED", response.json().get("data").get("status").asString());
        assertEquals(before, updatedAt(reservationId));
        assertEquals(0, historyCount(reservationId));
        assertEquals(0, refundCount(reservationId));
    }

    @Test
    void 추첨_예매는_409다() throws Exception {
        var reservationId = confirmedReservation(userId, "LOTTERY");

        var response = cancel(reservationId, userId, UUID.randomUUID().toString());

        assertEquals(409, response.status());
        assertEquals("LOTTERY_RESERVATION_NOT_CANCELABLE", response.json().get("code").asString());
        assertEquals("CONFIRMED", reservationStatus(reservationId));
        assertEquals(0, refundCount(reservationId));
    }

    @Test
    void 멱등키가_없거나_UUID가_아니면_400이다() throws Exception {
        var reservationId = confirmedReservation(userId, "GENERAL");

        for (var key : new String[] {null, "not-a-uuid", "1-1-1-1-1"}) {
            var response = cancel(reservationId, userId, key);
            assertEquals(400, response.status());
            assertEquals("VALIDATION_FAILED", response.json().get("code").asString());
        }
        assertEquals("CONFIRMED", reservationStatus(reservationId));
    }

    @Test
    void ID가_0_이하면_400이고_로그인하지_않으면_401이다() throws Exception {
        assertEquals(400, cancel(0L, userId, UUID.randomUUID().toString()).status());
        assertEquals(401, cancel(1L, null, UUID.randomUUID().toString()).status());
    }

    @Test
    void 취소한_뒤에는_같은_좌석으로_새_예매_좌석을_넣을_수_있다() throws Exception {
        var reservationId = confirmedReservation(userId, "GENERAL");
        assertEquals(202, cancel(reservationId, userId, UUID.randomUUID().toString()).status());
        var otherUser = userId + 1;
        var newReservationId = reservation(otherUser, "HELD", "GENERAL", scheduleId);

        try {
            reservationSeat(newReservationId, seatA, "HELD");
            reservationSeat(newReservationId, seatB, "HELD");
        } catch (DataIntegrityViolationException exception) {
            throw new AssertionError("취소한 좌석이 부분 유니크 인덱스에서 빠지지 않았습니다", exception);
        } finally {
            jdbc.update("DELETE FROM reservation_seats WHERE reservation_id = ?", newReservationId);
            jdbc.update("DELETE FROM reservations WHERE id = ?", newReservationId);
        }
    }

    @Test
    void 예매_상세_응답에_결제_ID가_실린다() throws Exception {
        var reservationId = confirmedReservation(userId, "GENERAL");

        var data = get("/api/v1/reservations/" + reservationId, userId).json().get("data");

        assertEquals(PAYMENT_ID, data.get("paymentId").asLong());
        var held = reservation(userId, "HELD", "GENERAL", scheduleId);
        assertTrue(get("/api/v1/reservations/" + held, userId).json().get("data").get("paymentId").isNull());
    }

    private Long confirmedReservation(long ownerId, String source) {
        var reservationId = reservation(ownerId, "CONFIRMED", source, scheduleId);
        ticket(reservationSeat(reservationId, seatA, "CONFIRMED"), "T-ISE206-A-" + reservationId);
        ticket(reservationSeat(reservationId, seatB, "CONFIRMED"), "T-ISE206-B-" + reservationId);
        // 같은 회차에 다른 예매로 2매를 더 가진 것으로 둔다. 0으로 끝나면 두 번 빼도(차감 조건이 막아) 0이라 구별되지 않는다
        jdbc.update("INSERT INTO user_schedule_quotas (user_id, schedule_id, held_count, created_at, updated_at)"
                + " VALUES (?, ?, 4, now(), now()) ON CONFLICT (user_id, schedule_id) DO NOTHING", ownerId, scheduleId);
        return reservationId;
    }

    private Long schedule(Instant startsAt) {
        return jdbc.queryForObject("INSERT INTO schedules (concert_id, venue_id, starts_at, open_at, version,"
                + " created_at, updated_at) VALUES (?, ?, ?, ?, 0, now(), now()) RETURNING id", Long.class,
                concertId, venueId, Timestamp.from(startsAt), Timestamp.from(startsAt.minusSeconds(86_400)));
    }

    private Long scheduleSeat(Long targetScheduleId, int seatNo, String status) {
        var venueSeatId = jdbc.queryForObject("INSERT INTO venue_seats (venue_id, section, row_no, seat_no, grade,"
                + " created_at, updated_at) VALUES (?, 'A', 1, ?, 'VIP', now(), now()) RETURNING id", Long.class,
                venueId, seatNo);
        return jdbc.queryForObject("INSERT INTO schedule_seats (schedule_id, venue_seat_id, section, grade, price,"
                + " status, created_at, updated_at) VALUES (?, ?, 'A', 'VIP', 165000, ?, now(), now())"
                + " RETURNING id", Long.class, targetScheduleId, venueSeatId, status);
    }

    private Long reservation(long ownerId, String status, String source, Long targetScheduleId) {
        return jdbc.queryForObject("INSERT INTO reservations (reservation_no, request_id, user_id, schedule_id, status,"
                + " quantity, total_amount, hold_expires_at, payment_id, source, created_at, updated_at)"
                + " VALUES (substr(md5(random()::text), 1, 20), gen_random_uuid(), ?, ?, ?, 2, 330000,"
                + " now() + interval '5 minutes', ?, ?, now() - interval '1 hour', now() - interval '1 hour')"
                + " RETURNING id", Long.class, ownerId, targetScheduleId, status,
                "CONFIRMED".equals(status) ? PAYMENT_ID : null, source);
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

    private String reservationStatus(Long reservationId) {
        return jdbc.queryForObject("SELECT status FROM reservations WHERE id = ?", String.class, reservationId);
    }

    private Instant updatedAt(Long reservationId) {
        return jdbc.queryForObject("SELECT updated_at FROM reservations WHERE id = ?", Timestamp.class,
                reservationId).toInstant();
    }

    private List<String> reservationSeatStatuses(Long reservationId) {
        return jdbc.queryForList("SELECT status FROM reservation_seats WHERE reservation_id = ? ORDER BY id",
                String.class, reservationId);
    }

    private List<String> scheduleSeatStatuses() {
        return jdbc.queryForList("SELECT status FROM schedule_seats WHERE id IN (?, ?) ORDER BY id", String.class,
                seatA, seatB);
    }

    private List<String> ticketStatuses(Long reservationId) {
        return jdbc.queryForList("SELECT t.status FROM tickets t JOIN reservation_seats rs"
                + " ON rs.id = t.reservation_seat_id WHERE rs.reservation_id = ? ORDER BY t.id", String.class,
                reservationId);
    }

    private int heldCount() {
        return jdbc.queryForObject("SELECT held_count FROM user_schedule_quotas WHERE user_id = ? AND schedule_id = ?",
                Integer.class, userId, scheduleId);
    }

    private int historyCount(Long reservationId) {
        return jdbc.queryForObject("SELECT count(*) FROM reservation_status_histories WHERE reservation_id = ?"
                + " AND from_status = 'CONFIRMED' AND to_status = 'CANCELED'", Integer.class, reservationId);
    }

    private int refundCount(Long reservationId) {
        return jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE topic = 'payment.command'"
                + " AND event_type = 'RefundRequested' AND message_key = ?", Integer.class,
                String.valueOf(reservationId));
    }

    private Response cancel(Long reservationId, Long requester, String idempotencyKey) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(
                "http://127.0.0.1:" + port + "/api/v1/reservations/" + reservationId + "/cancel"))
                .POST(HttpRequest.BodyPublishers.noBody());
        if (requester != null) {
            builder.header("X-User-Id", requester.toString()).header("X-User-Role", "USER");
        }
        if (idempotencyKey != null) {
            builder.header("Idempotency-Key", idempotencyKey);
        }
        var response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        return new Response(response.statusCode(), response.body());
    }

    private Response get(String path, Long requester) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET()
                .header("X-User-Id", requester.toString()).header("X-User-Role", "USER");
        var response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        return new Response(response.statusCode(), response.body());
    }

    private record Response(int status, String body) {
        JsonNode json() {
            return JsonMapper.builder().build().readTree(body);
        }
    }
}
