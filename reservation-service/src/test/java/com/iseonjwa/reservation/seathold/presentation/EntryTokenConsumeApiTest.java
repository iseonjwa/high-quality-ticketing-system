package com.iseonjwa.reservation.seathold.presentation;

import com.iseonjwa.reservation.queue.domain.EntryTokenJti;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

// 입장 토큰을 좌석 선점이 성공한 순간 소모하는지 실제 HTTP, PostgreSQL, Redis로 확인한다(ISE-207, ADR 0053)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.config.import=", "spring.cloud.config.enabled=false", "eureka.client.enabled=false"})
@ActiveProfiles("test")
class EntryTokenConsumeApiTest {
    private static final long USER = 52L;
    private static final long OTHER_USER = 53L;
    private static final long SEAT_PRICE = 165000L;

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final HttpClient http = HttpClient.newHttpClient();
    private final List<Long> createdVenueIds = new ArrayList<>();

    @LocalServerPort
    int port;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    StringRedisTemplate redis;

    private Long scheduleId;
    private List<Long> seatIds;

    @BeforeEach
    void setUp() {
        scheduleId = createSchedule();
        seatIds = createSeats(scheduleId, 4);
    }

    @AfterEach
    void cleanUp() {
        redis.delete(issuedKey());
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
        jdbc.update("DELETE FROM concerts WHERE title = 'ISE-207 토큰 소모 테스트' AND id NOT IN (SELECT concert_id FROM schedules)");
    }

    @Test
    void 선점에_성공하면_같은_토큰으로_다른_멱등키의_두_번째_선점은_403_ENTRY_TOKEN_USED다() throws Exception {
        var jti = issue(USER, 1_000L);

        var first = hold(USER, UUID.randomUUID(), List.of(seatIds.get(0)), jti);
        var second = hold(USER, UUID.randomUUID(), List.of(seatIds.get(1)), jti);

        assertEquals(201, first.status(), first.json().toString());
        assertFailure(second, 403, "ENTRY_TOKEN_USED");
        assertEquals(jti, jdbc.queryForObject("SELECT entry_token_jti FROM seat_hold_requests WHERE request_id = ?",
                String.class, UUID.fromString(first.json().get("data").get("requestId").asString())));
        assertEquals("AVAILABLE", seatStatus(seatIds.get(1)));
    }

    @Test
    void 이미_선점된_좌석으로_실패한_선점은_토큰을_소모하지_않는다() throws Exception {
        assertEquals(201, hold(OTHER_USER, UUID.randomUUID(), List.of(seatIds.get(0)), null).status());
        var jti = issue(USER, 2_000L);

        var failed = hold(USER, UUID.randomUUID(), List.of(seatIds.get(0)), jti);
        var retried = hold(USER, UUID.randomUUID(), List.of(seatIds.get(1)), jti);

        assertFailure(failed, 409, "SEAT_ALREADY_HELD");
        assertEquals(201, retried.status(), retried.json().toString());
    }

    @Test
    void 같은_멱등키_재요청은_소모_뒤에도_같은_결과로_성공한다() throws Exception {
        var jti = issue(USER, 3_000L);
        var key = UUID.randomUUID();

        var first = hold(USER, key, List.of(seatIds.get(0)), jti);
        var retried = hold(USER, key, List.of(seatIds.get(0)), jti);

        assertEquals(201, first.status(), first.json().toString());
        assertEquals(201, retried.status(), retried.json().toString());
        assertEquals(first.json().get("data").get("reservationId").asLong(),
                retried.json().get("data").get("reservationId").asLong());
    }

    @Test
    void 같은_토큰으로_동시에_두_번_선점하면_하나만_성공한다() throws Exception {
        var jti = issue(USER, 4_000L);
        var start = new CountDownLatch(1);
        Callable<Response> first = () -> {
            start.await();
            return hold(USER, UUID.randomUUID(), List.of(seatIds.get(0)), jti);
        };
        Callable<Response> second = () -> {
            start.await();
            return hold(USER, UUID.randomUUID(), List.of(seatIds.get(1)), jti);
        };

        List<Response> responses;
        try (var executor = Executors.newFixedThreadPool(2)) {
            var futures = List.of(executor.submit(first), executor.submit(second));
            start.countDown();
            responses = List.of(futures.get(0).get(), futures.get(1).get());
        }

        var statuses = responses.stream().map(Response::status).sorted().toList();
        assertEquals(List.of(201, 403), statuses, responses.toString());
        var rejected = responses.stream().filter(response -> response.status() == 403).findFirst().orElseThrow();
        assertEquals("ENTRY_TOKEN_USED", rejected.json().get("code").asString());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM seat_hold_requests WHERE entry_token_jti = ?",
                Integer.class, jti));
    }

    @Test
    void 선점에_성공하면_대기열_issued에서_그_토큰의_기록을_지운다() throws Exception {
        var jti = issue(USER, 5_000L);

        assertEquals(201, hold(USER, UUID.randomUUID(), List.of(seatIds.get(0)), jti).status());

        assertNull(redis.opsForZSet().score(issuedKey(), String.valueOf(USER)));
    }

    @Test
    void 옛_토큰이_거절될_때_새로_받은_토큰의_issued_기록은_남는다() throws Exception {
        var oldJti = issue(USER, 6_000L);
        assertEquals(201, hold(USER, UUID.randomUUID(), List.of(seatIds.get(0)), oldJti).status());
        // 다시 줄을 서서 새 토큰을 받았다
        issue(USER, 7_000L);

        var stale = hold(USER, UUID.randomUUID(), List.of(seatIds.get(1)), oldJti);

        assertFailure(stale, 403, "ENTRY_TOKEN_USED");
        assertEquals(7_000.0, redis.opsForZSet().score(issuedKey(), String.valueOf(USER)));
    }

    @Test
    void 커밋_뒤_정리가_빠졌으면_같은_토큰을_거절할_때_다시_지운다() throws Exception {
        var jti = issue(USER, 8_000L);
        assertEquals(201, hold(USER, UUID.randomUUID(), List.of(seatIds.get(0)), jti).status());
        // 대기열 Redis 장애로 정리가 빠진 상황을 만든다
        issue(USER, 8_000L);

        assertFailure(hold(USER, UUID.randomUUID(), List.of(seatIds.get(1)), jti), 403, "ENTRY_TOKEN_USED");

        assertNull(redis.opsForZSet().score(issuedKey(), String.valueOf(USER)));
    }

    @Test
    void 입장_토큰_헤더가_없으면_소모하지_않고_지금처럼_선점한다() throws Exception {
        var first = hold(USER, UUID.randomUUID(), List.of(seatIds.get(0)), null);
        var second = hold(USER, UUID.randomUUID(), List.of(seatIds.get(1)), null);

        assertEquals(201, first.status(), first.json().toString());
        assertEquals(201, second.status(), second.json().toString());
        assertTrue(jdbc.queryForList("SELECT entry_token_jti FROM seat_hold_requests WHERE schedule_id = ?",
                String.class, scheduleId).stream().allMatch(value -> value == null));
    }

    @Test
    void 같은_jti는_거절되지_않은_행에_한_번만_들어가고_REJECTED_행은_막지_않는다() {
        var jti = "d".repeat(32);
        insertRequest(UUID.randomUUID(), "REJECTED", jti);
        insertRequest(UUID.randomUUID(), "REQUESTED", jti);

        assertThrows(DuplicateKeyException.class, () -> insertRequest(UUID.randomUUID(), "HELD", jti));
    }

    private void insertRequest(UUID key, String status, String jti) {
        jdbc.update("INSERT INTO seat_hold_requests (request_id, user_id, schedule_id, seat_ids, status,"
                + " entry_token_jti, created_at, updated_at) VALUES (?, ?, ?, ?::bigint[], ?, ?, now(), now())",
                key, USER, scheduleId, "{" + seatIds.get(0) + "}", status, jti);
    }

    // 대기열이 토큰을 내준 것과 같은 상태(issued에 발급 시각)를 만들고, 그 발급으로 서명했을 jti를 돌려준다
    private String issue(long userId, long issuedAtMillis) {
        redis.opsForZSet().add(issuedKey(), String.valueOf(userId), issuedAtMillis);
        return EntryTokenJti.of(scheduleId, userId, issuedAtMillis);
    }

    private String issuedKey() {
        return "queue:{" + scheduleId + "}:issued";
    }

    private Long createSchedule() {
        var venueId = jdbc.queryForObject("INSERT INTO venues (name, created_at, updated_at)"
                + " VALUES ('ISE-207 테스트 공연장', now(), now()) RETURNING id", Long.class);
        createdVenueIds.add(venueId);
        var concertId = jdbc.queryForObject("INSERT INTO concerts (title, version, created_at, updated_at)"
                + " VALUES ('ISE-207 토큰 소모 테스트', 0, now(), now()) RETURNING id", Long.class);
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
                    + " status, created_at, updated_at) VALUES (?, ?, 'A', 'VIP', ?, 'AVAILABLE', now(), now())"
                    + " RETURNING id", Long.class, scheduleId, venueSeatId, SEAT_PRICE));
        }
        return ids;
    }

    private String seatStatus(Long seatId) {
        return jdbc.queryForObject("SELECT status FROM schedule_seats WHERE id = ?", String.class, seatId);
    }

    private Response hold(long userId, UUID key, List<Long> seats, String entryTokenJti) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
                        + "/api/v1/schedules/" + scheduleId + "/reservations"))
                .header("Content-Type", "application/json")
                .header("X-User-Id", String.valueOf(userId))
                .header("X-User-Role", "USER")
                .header("Idempotency-Key", key.toString())
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(Map.of("seatIds", seats))));
        if (entryTokenJti != null) {
            request.header("X-Entry-Token-Id", entryTokenJti);
        }
        var response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        return new Response(response.statusCode(), mapper.readTree(response.body()));
    }

    private static void assertFailure(Response response, int status, String code) {
        assertEquals(status, response.status(), response.json().toString());
        assertEquals(code, response.json().get("code").asString(), response.json().toString());
        assertFalse(response.json().has("data"), response.json().toString());
    }

    private record Response(int status, JsonNode json) {}
}
