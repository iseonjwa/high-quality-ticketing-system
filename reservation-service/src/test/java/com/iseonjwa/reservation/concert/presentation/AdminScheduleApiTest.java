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

import static org.junit.jupiter.api.Assertions.assertEquals;

// 운영자 회차 등록 API(SA 11-5). 공연·공연장은 DB에 직접 넣어 공연 등록 API와 독립으로 확인한다.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.config.import=", "spring.cloud.config.enabled=false", "eureka.client.enabled=false"})
@ActiveProfiles("test")
class AdminScheduleApiTest {
    private static final long ADMIN_ID = 9002L;
    private static final String MARK = "회차 API 테스트";
    private static final String STARTS_AT = "2026-12-20T10:00:00Z";
    private static final String OPEN_AT = "2026-12-01T11:00:00Z";

    private final HttpClient http = HttpClient.newHttpClient();

    @LocalServerPort
    int port;
    @Autowired
    JdbcTemplate jdbc;

    private Long concertId;
    private Long venueId;

    @BeforeEach
    void setUp() {
        concertId = jdbc.queryForObject("INSERT INTO concerts (title, version, created_at, updated_at)"
                + " VALUES (?, 0, now(), now()) RETURNING id", Long.class, MARK);
        venueId = jdbc.queryForObject("INSERT INTO venues (name, created_at, updated_at)"
                + " VALUES (?, now(), now()) RETURNING id", Long.class, MARK);
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM schedules WHERE concert_id IN (SELECT id FROM concerts WHERE title = ?)", MARK);
        jdbc.update("DELETE FROM concerts WHERE title = ?", MARK);
        jdbc.update("DELETE FROM venues WHERE name = ?", MARK);
    }

    @Test
    void 운영자가_회차를_등록하면_201과_회차_ID를_돌려준다() throws Exception {
        var response = post(concertId, ADMIN_ID, "ADMIN", body(venueId, STARTS_AT, OPEN_AT, "2", "10"));

        assertEquals(201, response.status());
        var scheduleId = response.json().get("data").get("scheduleId").asLong();
        var row = jdbc.queryForMap("SELECT concert_id, venue_id, max_per_user, hold_minutes, version, created_by"
                + " FROM schedules WHERE id = ?", scheduleId);
        assertEquals(concertId, row.get("concert_id"));
        assertEquals(venueId, row.get("venue_id"));
        assertEquals(2, ((Number) row.get("max_per_user")).intValue());
        assertEquals(10, ((Number) row.get("hold_minutes")).intValue());
        assertEquals(0L, row.get("version"));
        assertEquals(ADMIN_ID, row.get("created_by"));
    }

    @Test
    void 매수_한도와_선점_시간을_비우면_기본값_4와_5를_쓴다() throws Exception {
        var response = post(concertId, ADMIN_ID, "ADMIN", body(venueId, STARTS_AT, OPEN_AT, null, null));

        assertEquals(201, response.status());
        var scheduleId = response.json().get("data").get("scheduleId").asLong();
        var row = jdbc.queryForMap("SELECT max_per_user, hold_minutes FROM schedules WHERE id = ?", scheduleId);
        assertEquals(4, ((Number) row.get("max_per_user")).intValue());
        assertEquals(5, ((Number) row.get("hold_minutes")).intValue());
    }

    @Test
    void 없는_공연이나_공연장이면_404다() throws Exception {
        var noConcert = post(Long.MAX_VALUE, ADMIN_ID, "ADMIN", body(venueId, STARTS_AT, OPEN_AT, null, null));
        var noVenue = post(concertId, ADMIN_ID, "ADMIN", body(Long.MAX_VALUE, STARTS_AT, OPEN_AT, null, null));

        assertEquals(404, noConcert.status());
        assertEquals("NOT_FOUND", noConcert.json().get("code").asString());
        assertEquals(404, noVenue.status());
        assertEquals(0, countSaved());
    }

    @Test
    void 지운_공연에는_회차를_등록할_수_없다() throws Exception {
        jdbc.update("UPDATE concerts SET deleted_at = now() WHERE id = ?", concertId);

        var response = post(concertId, ADMIN_ID, "ADMIN", body(venueId, STARTS_AT, OPEN_AT, null, null));

        assertEquals(404, response.status());
    }

    @Test
    void 예매_오픈이_공연_시작과_같거나_뒤면_400이다() throws Exception {
        var same = post(concertId, ADMIN_ID, "ADMIN", body(venueId, STARTS_AT, STARTS_AT, null, null));
        var after = post(concertId, ADMIN_ID, "ADMIN", body(venueId, OPEN_AT, STARTS_AT, null, null));

        assertEquals(400, same.status());
        assertEquals("VALIDATION_FAILED", same.json().get("code").asString());
        assertEquals(400, after.status());
        assertEquals(0, countSaved());
    }

    @Test
    void 필수값이_없거나_한도가_1보다_작으면_400이다() throws Exception {
        var noStartsAt = post(concertId, ADMIN_ID, "ADMIN", body(venueId, null, OPEN_AT, null, null));
        var zeroMax = post(concertId, ADMIN_ID, "ADMIN", body(venueId, STARTS_AT, OPEN_AT, "0", null));
        var zeroHold = post(concertId, ADMIN_ID, "ADMIN", body(venueId, STARTS_AT, OPEN_AT, null, "0"));

        assertEquals(400, noStartsAt.status());
        assertEquals(400, zeroMax.status());
        assertEquals(400, zeroHold.status());
        assertEquals(0, countSaved());
    }

    @Test
    void 일반_사용자는_403이다() throws Exception {
        var response = post(concertId, 1L, "USER", body(venueId, STARTS_AT, OPEN_AT, null, null));

        assertEquals(403, response.status());
        assertEquals(0, countSaved());
    }

    private int countSaved() {
        return jdbc.queryForObject("SELECT count(*) FROM schedules WHERE concert_id = ?", Integer.class, concertId);
    }

    // null인 값은 본문에서 뺀다
    private static String body(Long venueId, String startsAt, String openAt, String maxPerUser, String holdMinutes) {
        var fields = new java.util.ArrayList<String>();
        fields.add("\"venueId\": " + venueId);
        if (startsAt != null) fields.add("\"startsAt\": \"" + startsAt + "\"");
        if (openAt != null) fields.add("\"openAt\": \"" + openAt + "\"");
        if (maxPerUser != null) fields.add("\"maxPerUser\": " + maxPerUser);
        if (holdMinutes != null) fields.add("\"holdMinutes\": " + holdMinutes);
        return "{" + String.join(", ", fields) + "}";
    }

    private Response post(Long concertId, Long userId, String role, String body) throws Exception {
        var uri = URI.create("http://127.0.0.1:" + port + "/api/v1/admin/concerts/" + concertId + "/schedules");
        var request = HttpRequest.newBuilder(uri)
                .header("Content-Type", "application/json")
                .header("X-User-Id", Long.toString(userId))
                .header("X-User-Role", role)
                .POST(HttpRequest.BodyPublishers.ofString(body));
        var response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        return new Response(response.statusCode(), response.body());
    }

    private record Response(int status, String body) {
        JsonNode json() {
            return JsonMapper.builder().build().readTree(body);
        }
    }
}
