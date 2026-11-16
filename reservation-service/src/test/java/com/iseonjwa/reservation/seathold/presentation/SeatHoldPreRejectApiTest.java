package com.iseonjwa.reservation.seathold.presentation;

import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// 좌석 조회의 1초 캐시로 이미 잡힌 좌석의 선점을 트랜잭션 전에 거절하는지 실제 HTTP로 본다(ISE-281).
// 캐시는 좌석 조회(GET seats)가 채우므로 각 시나리오는 좌석 조회를 먼저 부른다
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.config.import=", "spring.cloud.config.enabled=false", "eureka.client.enabled=false"})
@ActiveProfiles("test")
class SeatHoldPreRejectApiTest {
    private static final long USER = 81L;
    private static final long OTHER_USER = 82L;
    // 캐시 TTL(1초)보다 조금 길게 기다려 낡은 캐시를 쓰지 않는지 본다
    private static final long PAST_CACHE_TTL_MILLIS = 1_200;

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final HttpClient http = HttpClient.newHttpClient();
    private final List<Long> createdVenueIds = new ArrayList<>();

    @LocalServerPort
    int port;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    MeterRegistry meters;

    private Long scheduleId;
    private List<Long> seatIds;

    @BeforeEach
    void setUp() {
        scheduleId = createSchedule();
        seatIds = createSeats(scheduleId, 2);
    }

    @AfterEach
    void cleanUp() {
        for (var venueId : createdVenueIds) {
            var scheduleIds = "(SELECT id FROM schedules WHERE venue_id = ?)";
            var reservationIds = "(SELECT id FROM reservations WHERE schedule_id IN " + scheduleIds + ")";
            jdbc.update("DELETE FROM seat_hold_requests WHERE schedule_id IN " + scheduleIds, venueId);
            jdbc.update("DELETE FROM reservation_status_histories WHERE reservation_id IN " + reservationIds, venueId);
            jdbc.update("DELETE FROM reservation_seats WHERE reservation_id IN " + reservationIds, venueId);
            jdbc.update("DELETE FROM reservations WHERE schedule_id IN " + scheduleIds, venueId);
            jdbc.update("DELETE FROM user_schedule_quotas WHERE schedule_id IN " + scheduleIds, venueId);
            jdbc.update("DELETE FROM schedule_seats WHERE schedule_id IN " + scheduleIds, venueId);
            jdbc.update("DELETE FROM schedules WHERE venue_id = ?", venueId);
            jdbc.update("DELETE FROM venue_seats WHERE venue_id = ?", venueId);
            jdbc.update("DELETE FROM venues WHERE id = ?", venueId);
        }
        jdbc.update("DELETE FROM concerts WHERE title = 'ISE-281 사전 거절 테스트'"
                + " AND id NOT IN (SELECT concert_id FROM schedules)");
    }

    @Test
    void 캐시에서_이미_잡힌_좌석은_선점_요청_행을_남기지_않고_같은_409_형식으로_거절한다() throws Exception {
        assertEquals(201, hold(USER, UUID.randomUUID(), List.of(seatIds.get(0))).statusCode());
        assertEquals(200, getSeats());
        var preRejected = preRejected();
        var conflict = count("seat_conflict");

        var rejectedKey = UUID.randomUUID();
        var response = hold(OTHER_USER, rejectedKey, List.of(seatIds.get(0)));

        assertEquals(409, response.statusCode());
        assertTrue(response.body().contains("SEAT_ALREADY_HELD"), response.body());
        assertEquals(preRejected + 1, preRejected());
        // 응답 결과 지표는 지금처럼 좌석 충돌로 센다. 사전 거절 수는 그 안의 일부다
        assertEquals(conflict + 1, count("seat_conflict"));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM seat_hold_requests WHERE request_id = ?",
                Integer.class, rejectedKey));
    }

    @Test
    void 성공한_선점의_같은_멱등키_재시도는_캐시가_이미_잡힘이어도_201로_재응답한다() throws Exception {
        var key = UUID.randomUUID();
        assertEquals(201, hold(USER, key, List.of(seatIds.get(0))).statusCode());
        assertEquals(200, getSeats());
        var preRejected = preRejected();

        assertEquals(201, hold(USER, key, List.of(seatIds.get(0))).statusCode());

        assertEquals(preRejected, preRejected());
    }

    @Test
    void 캐시가_선점_가능이라_하는_좌석은_거절하지_않는다() throws Exception {
        assertEquals(200, getSeats());
        var preRejected = preRejected();

        assertEquals(201, hold(OTHER_USER, UUID.randomUUID(), List.of(seatIds.get(1))).statusCode());
        // 캐시를 만든 뒤 잡힌 좌석은 캐시에 아직 가능으로 보여 DB의 조건부 UPDATE가 막는다
        assertEquals(409, hold(USER, UUID.randomUUID(), List.of(seatIds.get(1))).statusCode());

        assertEquals(preRejected, preRejected());
    }

    @Test
    void 캐시가_1초보다_낡으면_사전_거절하지_않고_DB로_판정한다() throws Exception {
        // 잡혀 있던 좌석이 캐시에 담긴 뒤 풀린 상황을 좌석 상태만 바꿔 만든다(예매 행이 없어야 다시 잡을 수 있다)
        jdbc.update("UPDATE schedule_seats SET status = 'HELD' WHERE id = ?", seatIds.get(0));
        assertEquals(200, getSeats());
        jdbc.update("UPDATE schedule_seats SET status = 'AVAILABLE' WHERE id = ?", seatIds.get(0));

        // 1초 안에는 캐시가 아직 잡힘이라 거절한다. 사전 거절이 감수하는 최대 1초 낡은 409다
        assertEquals(409, hold(OTHER_USER, UUID.randomUUID(), List.of(seatIds.get(0))).statusCode());
        Thread.sleep(PAST_CACHE_TTL_MILLIS);
        var preRejected = preRejected();

        assertEquals(201, hold(OTHER_USER, UUID.randomUUID(), List.of(seatIds.get(0))).statusCode());
        assertEquals(preRejected, preRejected());
    }

    private double preRejected() {
        return meters.get("seathold.pre_rejected").counter().count();
    }

    private double count(String outcome) {
        return meters.get("seathold.result").tag("outcome", outcome).counter().count();
    }

    private int getSeats() throws Exception {
        var request = HttpRequest.newBuilder(URI.create(base() + "/api/v1/schedules/" + scheduleId + "/seats"))
                .header("X-User-Id", String.valueOf(USER))
                .header("X-User-Role", "USER")
                .GET().build();
        return http.send(request, HttpResponse.BodyHandlers.ofString()).statusCode();
    }

    private HttpResponse<String> hold(long userId, UUID key, List<Long> seats) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(base() + "/api/v1/schedules/" + scheduleId + "/reservations"))
                .header("Content-Type", "application/json")
                .header("X-User-Id", String.valueOf(userId))
                .header("X-User-Role", "USER")
                .header("Idempotency-Key", key.toString())
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(Map.of("seatIds", seats))))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private String base() {
        return "http://127.0.0.1:" + port;
    }

    private Long createSchedule() {
        var venueId = jdbc.queryForObject("INSERT INTO venues (name, created_at, updated_at)"
                + " VALUES ('ISE-281 테스트 공연장', now(), now()) RETURNING id", Long.class);
        createdVenueIds.add(venueId);
        var concertId = jdbc.queryForObject("INSERT INTO concerts (title, version, created_at, updated_at)"
                + " VALUES ('ISE-281 사전 거절 테스트', 0, now(), now()) RETURNING id", Long.class);
        return jdbc.queryForObject("INSERT INTO schedules (concert_id, venue_id, starts_at, open_at, version,"
                + " created_at, updated_at) VALUES (?, ?, now() + interval '1 day', now() - interval '1 minute', 0,"
                + " now(), now()) RETURNING id", Long.class, concertId, venueId);
    }

    private List<Long> createSeats(Long scheduleId, int count) {
        var venueId = jdbc.queryForObject("SELECT venue_id FROM schedules WHERE id = ?", Long.class, scheduleId);
        var ids = new ArrayList<Long>();
        for (var i = 0; i < count; i++) {
            var venueSeatId = jdbc.queryForObject("INSERT INTO venue_seats (venue_id, section, row_no, seat_no, grade,"
                    + " created_at, updated_at) VALUES (?, 'A', 1, ?, 'VIP', now(), now()) RETURNING id",
                    Long.class, venueId, i + 1);
            ids.add(jdbc.queryForObject("INSERT INTO schedule_seats (schedule_id, venue_seat_id, section, grade, price,"
                    + " status, created_at, updated_at) VALUES (?, ?, 'A', 'VIP', 165000, 'AVAILABLE', now(), now())"
                    + " RETURNING id", Long.class, scheduleId, venueSeatId));
        }
        return ids;
    }
}
