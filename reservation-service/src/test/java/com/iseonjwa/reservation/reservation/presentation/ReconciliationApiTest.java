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
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// 대사용 내부 조회 API(ISE-75, ADR 0029). 예매는 JDBC로 상태·갱신 시각을 직접 넣는다.
// 기간 조회는 다른 테스트 데이터와 겹치지 않게 2001년 시각을 쓴다
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.config.import=", "spring.cloud.config.enabled=false", "eureka.client.enabled=false"})
@ActiveProfiles("test")
class ReconciliationApiTest {
    private static final String MARK = "ISE-75 대사 API 테스트";
    private static final Instant BASE = Instant.parse("2001-01-01T00:00:00Z");

    private final HttpClient http = HttpClient.newHttpClient();

    @LocalServerPort
    int port;
    @Autowired
    JdbcTemplate jdbc;

    private Long venueId;
    private Long scheduleId;

    @BeforeEach
    void setUp() {
        venueId = jdbc.queryForObject("INSERT INTO venues (name, created_at, updated_at)"
                + " VALUES (?, now(), now()) RETURNING id", Long.class, MARK);
        var concertId = jdbc.queryForObject("INSERT INTO concerts (title, version, created_at, updated_at)"
                + " VALUES (?, 0, now(), now()) RETURNING id", Long.class, MARK);
        scheduleId = jdbc.queryForObject("INSERT INTO schedules (concert_id, venue_id, starts_at, open_at, version,"
                + " created_at, updated_at) VALUES (?, ?, now() + interval '1 day', now() - interval '1 minute',"
                + " 0, now(), now()) RETURNING id", Long.class, concertId, venueId);
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM reservations WHERE schedule_id = ?", scheduleId);
        jdbc.update("DELETE FROM schedules WHERE venue_id = ?", venueId);
        jdbc.update("DELETE FROM concerts WHERE title = ?", MARK);
        jdbc.update("DELETE FROM venues WHERE id = ?", venueId);
    }

    @Test
    void ID_목록의_예매를_ID_순으로_돌려주고_없는_ID는_뺀다() throws Exception {
        var confirmed = insert("CONFIRMED", 9001L, BASE);
        var held = insert("HELD", null, BASE);

        var response = get("/internal/reconciliation/reservations?ids=" + held + "," + confirmed + ",999999999");

        assertEquals(200, response.status());
        var data = response.json().get("data");
        assertEquals(2, data.size());
        var first = data.get(0);
        assertEquals(confirmed.longValue(), first.get("reservationId").asLong());
        assertEquals(42L, first.get("userId").asLong());
        assertEquals("CONFIRMED", first.get("status").asString());
        assertEquals(330000L, first.get("totalAmount").asLong());
        assertEquals(9001L, first.get("paymentId").asLong());
        assertEquals(BASE, Instant.parse(first.get("updatedAt").asString()));
        assertTrue(first.has("holdExpiresAt"));
        assertEquals(held.longValue(), data.get(1).get("reservationId").asLong());
        assertTrue(data.get(1).get("paymentId").isNull());
    }

    @Test
    void ID가_없거나_100건을_넘으면_400이다() throws Exception {
        var ids = LongStream.rangeClosed(1, 101).mapToObj(Long::toString).collect(Collectors.joining(","));

        assertEquals(400, get("/internal/reconciliation/reservations?ids=" + ids).status());
        assertEquals(400, get("/internal/reconciliation/reservations").status());
    }

    @Test
    void 기간_안에_확정_취소된_예매만_돌려준다() throws Exception {
        var confirmed = insert("CONFIRMED", 9001L, BASE.plusSeconds(10));
        var canceled = insert("CANCELED", 9002L, BASE.plusSeconds(20));
        insert("HELD", null, BASE.plusSeconds(30));
        insert("EXPIRED", null, BASE.plusSeconds(40));
        // 기간 끝(to)은 포함하지 않는다
        insert("CONFIRMED", 9003L, BASE.plusSeconds(60));

        var response = changes(BASE, BASE.plusSeconds(60), null);

        assertEquals(200, response.status());
        var data = response.json().get("data");
        assertEquals(List.of(confirmed, canceled), ids(data.get("items")));
        assertTrue(data.get("nextCursor").isNull());
    }

    @Test
    void 한_쪽이_100건으로_차면_nextCursor로_다음_쪽을_읽는다() throws Exception {
        var all = new ArrayList<Long>();
        for (var i = 0; i < 101; i++) {
            all.add(insert("CONFIRMED", 10_000L + i, BASE.plusSeconds(i)));
        }

        var first = changes(BASE, BASE.plusSeconds(3600), null).json().get("data");
        var next = first.get("nextCursor").asLong();
        var second = changes(BASE, BASE.plusSeconds(3600), next).json().get("data");

        assertEquals(all.subList(0, 100), ids(first.get("items")));
        assertEquals(all.get(99).longValue(), next);
        assertEquals(List.of(all.get(100)), ids(second.get("items")));
        assertTrue(second.get("nextCursor").isNull());
    }

    @Test
    void 기간이_거꾸로거나_비면_400이다() throws Exception {
        assertEquals(400, changes(BASE.plusSeconds(60), BASE, null).status());
        assertEquals(400, changes(BASE, BASE, null).status());
        assertEquals(400, get("/internal/reconciliation/reservations/changes?from=" + BASE).status());
    }

    private Long insert(String status, Long paymentId, Instant updatedAt) {
        return jdbc.queryForObject("INSERT INTO reservations (reservation_no, request_id, user_id, schedule_id, status,"
                + " quantity, total_amount, hold_expires_at, payment_id, created_at, updated_at)"
                + " VALUES (substr(md5(random()::text), 1, 20), gen_random_uuid(), 42, ?, ?, 2, 330000,"
                + " ?, ?, ?, ?) RETURNING id", Long.class, scheduleId, status,
                Timestamp.from(updatedAt.plusSeconds(1800)), paymentId, Timestamp.from(updatedAt),
                Timestamp.from(updatedAt));
    }

    private Response changes(Instant from, Instant to, Long cursor) throws Exception {
        return get("/internal/reconciliation/reservations/changes?from=" + from + "&to=" + to
                + (cursor == null ? "" : "&cursor=" + cursor));
    }

    private static List<Long> ids(JsonNode items) {
        var ids = new ArrayList<Long>();
        for (var item : items) {
            ids.add(item.get("reservationId").asLong());
        }
        return ids;
    }

    private Response get(String path) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET().build();
        var response = http.send(request, HttpResponse.BodyHandlers.ofString());
        return new Response(response.statusCode(), response.body());
    }

    private record Response(int status, String body) {
        JsonNode json() {
            return JsonMapper.builder().build().readTree(body);
        }
    }
}
