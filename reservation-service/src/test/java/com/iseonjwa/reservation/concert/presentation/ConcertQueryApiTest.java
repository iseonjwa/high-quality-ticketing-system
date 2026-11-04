package com.iseonjwa.reservation.concert.presentation;

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
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// 공연 목록·상세 조회 API(SA 11-5). 로그인 헤더 없이 호출한다.
// 시각은 테스트 실행 시각 기준으로 넣어 판매 상태 세 가지(BEFORE_OPEN·ON_SALE·CLOSED)를 모두 만든다.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.config.import=", "spring.cloud.config.enabled=false", "eureka.client.enabled=false"})
@ActiveProfiles("test")
class ConcertQueryApiTest {
    private static final String MARK = "조회 API 테스트";
    private static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.SECONDS);

    private final HttpClient http = HttpClient.newHttpClient();

    @LocalServerPort
    int port;
    @Autowired
    JdbcTemplate jdbc;

    private Long venueId;
    private Long concertId;
    private Long closedId;
    private Long onSaleId;
    private Long beforeOpenId;
    private Long laterId;

    @BeforeEach
    void setUp() {
        venueId = jdbc.queryForObject("INSERT INTO venues (name, created_at, updated_at)"
                + " VALUES (?, now(), now()) RETURNING id", Long.class, MARK);
        concertId = insertConcert(MARK + " 공연", "설명");
        // 이미 끝난 회차, 판매 중인 회차, 오픈 전 회차, 더 뒤의 오픈 전 회차
        closedId = insertSchedule(concertId, NOW.minus(Duration.ofDays(10)), NOW.minus(Duration.ofDays(1)));
        onSaleId = insertSchedule(concertId, NOW.minus(Duration.ofDays(1)), NOW.plus(Duration.ofDays(5)));
        beforeOpenId = insertSchedule(concertId, NOW.plus(Duration.ofDays(1)), NOW.plus(Duration.ofDays(20)));
        laterId = insertSchedule(concertId, NOW.plus(Duration.ofDays(2)), NOW.plus(Duration.ofDays(30)));
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM schedules WHERE venue_id = ?", venueId);
        jdbc.update("DELETE FROM concerts WHERE title LIKE ?", MARK + "%");
        jdbc.update("DELETE FROM venues WHERE id = ?", venueId);
    }

    @Test
    void 목록은_공연마다_아직_시작하지_않은_가장_빠른_회차를_보여_준다() throws Exception {
        var response = get("/api/v1/concerts?page=0&size=100");

        assertEquals(200, response.status());
        var data = response.json().get("data");
        assertEquals(0, data.get("page").asInt());
        assertEquals(100, data.get("size").asInt());
        assertTrue(data.get("totalElements").asLong() >= 1);

        var item = find(data.get("content"), concertId);
        assertEquals(MARK + " 공연", item.get("title").asString());
        // 끝난 회차는 건너뛰고, 남은 회차 중 시작이 가장 빠른 판매 중 회차(5일 뒤 시작)
        assertEquals(NOW.plus(Duration.ofDays(5)), Instant.parse(item.get("nextStartsAt").asString()));
        assertEquals(NOW.minus(Duration.ofDays(1)), Instant.parse(item.get("nextOpenAt").asString()));
    }

    @Test
    void 남은_회차가_없는_공연은_다음_회차가_null이다() throws Exception {
        var noUpcoming = insertConcert(MARK + " 지난 공연", null);
        insertSchedule(noUpcoming, NOW.minus(Duration.ofDays(10)), NOW.minus(Duration.ofDays(1)));

        var item = find(get("/api/v1/concerts?size=100").json().get("data").get("content"), noUpcoming);

        assertTrue(item.get("nextStartsAt").isNull());
        assertTrue(item.get("nextOpenAt").isNull());
    }

    @Test
    void 목록은_최근_등록_순이고_지운_공연은_빠진다() throws Exception {
        var newer = insertConcert(MARK + " 새 공연", null);
        var deleted = insertConcert(MARK + " 지운 공연", null);
        jdbc.update("UPDATE concerts SET deleted_at = now() WHERE id = ?", deleted);

        var ids = ids(get("/api/v1/concerts?size=100").json().get("data").get("content"));

        assertTrue(ids.indexOf(newer) < ids.indexOf(concertId));
        assertFalse(ids.contains(deleted));
    }

    @Test
    void 상세는_회차를_시작_순으로_판매_상태와_함께_보여_준다() throws Exception {
        var response = get("/api/v1/concerts/" + concertId);

        assertEquals(200, response.status());
        var data = response.json().get("data");
        assertEquals(concertId, data.get("concertId").asLong());
        assertEquals("설명", data.get("description").asString());
        assertFalse(data.get("serverTime").isNull());

        var schedules = data.get("schedules");
        assertEquals(List.of(closedId, onSaleId, beforeOpenId, laterId), ids(schedules, "scheduleId"));
        assertEquals("CLOSED", schedules.get(0).get("saleStatus").asString());
        assertEquals("ON_SALE", schedules.get(1).get("saleStatus").asString());
        assertEquals("BEFORE_OPEN", schedules.get(2).get("saleStatus").asString());
        assertEquals(MARK, schedules.get(1).get("venueName").asString());
        assertEquals(4, schedules.get(1).get("maxPerUser").asInt());
    }

    @Test
    void 상세에서_지운_회차는_빠진다() throws Exception {
        jdbc.update("UPDATE schedules SET deleted_at = now() WHERE id = ?", laterId);

        var schedules = get("/api/v1/concerts/" + concertId).json().get("data").get("schedules");

        assertFalse(ids(schedules, "scheduleId").contains(laterId));
    }

    @Test
    void 없거나_지운_공연의_상세는_404다() throws Exception {
        jdbc.update("UPDATE concerts SET deleted_at = now() WHERE id = ?", concertId);

        assertEquals(404, get("/api/v1/concerts/" + Long.MAX_VALUE).status());
        assertEquals(404, get("/api/v1/concerts/" + concertId).status());
    }

    @Test
    void 페이지_번호가_음수이거나_크기가_범위를_벗어나면_400이다() throws Exception {
        assertEquals(400, get("/api/v1/concerts?page=-1").status());
        assertEquals(400, get("/api/v1/concerts?size=0").status());
        assertEquals(400, get("/api/v1/concerts?size=101").status());
    }

    private Long insertConcert(String title, String description) {
        return jdbc.queryForObject("INSERT INTO concerts (title, description, version, created_at, updated_at)"
                + " VALUES (?, ?, 0, now(), now()) RETURNING id", Long.class, title, description);
    }

    private Long insertSchedule(Long concertId, Instant openAt, Instant startsAt) {
        return jdbc.queryForObject("INSERT INTO schedules (concert_id, venue_id, starts_at, open_at, version,"
                + " created_at, updated_at) VALUES (?, ?, ?, ?, 0, now(), now()) RETURNING id", Long.class,
                concertId, venueId, Timestamp.from(startsAt), Timestamp.from(openAt));
    }

    private static JsonNode find(JsonNode content, Long concertId) {
        for (JsonNode item : content) {
            if (item.get("concertId").asLong() == concertId) {
                return item;
            }
        }
        throw new AssertionError("목록에 공연이 없다: " + concertId);
    }

    private static List<Long> ids(JsonNode content) {
        return ids(content, "concertId");
    }

    private static List<Long> ids(JsonNode items, String field) {
        var ids = new ArrayList<Long>();
        items.forEach(item -> ids.add(item.get(field).asLong()));
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
