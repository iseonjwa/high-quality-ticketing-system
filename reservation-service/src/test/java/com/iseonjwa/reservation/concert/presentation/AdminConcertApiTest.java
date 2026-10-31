package com.iseonjwa.reservation.concert.presentation;

import org.junit.jupiter.api.AfterEach;
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

// 운영자 공연 등록 API(SA 11-5). Gateway를 거치지 않고 Gateway가 넣는 사용자 헤더를 직접 붙여 호출한다.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.config.import=", "spring.cloud.config.enabled=false", "eureka.client.enabled=false"})
@ActiveProfiles("test")
class AdminConcertApiTest {
    private static final long ADMIN_ID = 9001L;
    private static final String TITLE_PREFIX = "API 테스트 공연";

    private final HttpClient http = HttpClient.newHttpClient();

    @LocalServerPort
    int port;
    @Autowired
    JdbcTemplate jdbc;

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM concerts WHERE title LIKE ?", TITLE_PREFIX + "%");
    }

    @Test
    void 운영자가_공연을_등록하면_201과_공연_ID를_돌려준다() throws Exception {
        var response = post(ADMIN_ID, "ADMIN", """
                {"title": "%s 1", "description": "첫 공연"}""".formatted(TITLE_PREFIX));

        assertEquals(201, response.status());
        var concertId = response.json().get("data").get("concertId").asLong();
        var row = jdbc.queryForMap("SELECT title, description, version, created_by FROM concerts WHERE id = ?",
                concertId);
        assertEquals(TITLE_PREFIX + " 1", row.get("title"));
        assertEquals("첫 공연", row.get("description"));
        assertEquals(0L, row.get("version"));
        assertEquals(ADMIN_ID, row.get("created_by"));
    }

    @Test
    void 설명은_비워도_된다() throws Exception {
        var response = post(ADMIN_ID, "ADMIN", """
                {"title": "%s 2"}""".formatted(TITLE_PREFIX));

        assertEquals(201, response.status());
    }

    @Test
    void 일반_사용자는_403이고_저장하지_않는다() throws Exception {
        var response = post(1L, "USER", """
                {"title": "%s 3"}""".formatted(TITLE_PREFIX));

        assertEquals(403, response.status());
        assertEquals("FORBIDDEN", response.json().get("code").asString());
        assertEquals(0, countSaved());
    }

    @Test
    void 사용자_헤더가_없으면_401이다() throws Exception {
        var response = post(null, null, """
                {"title": "%s 4"}""".formatted(TITLE_PREFIX));

        assertEquals(401, response.status());
        assertEquals(0, countSaved());
    }

    @Test
    void 제목이_비었거나_200자를_넘으면_400이다() throws Exception {
        var blank = post(ADMIN_ID, "ADMIN", """
                {"title": "  "}""");
        var tooLong = post(ADMIN_ID, "ADMIN", """
                {"title": "%s"}""".formatted(TITLE_PREFIX + "가".repeat(200)));

        assertEquals(400, blank.status());
        assertEquals("VALIDATION_FAILED", blank.json().get("code").asString());
        assertEquals(400, tooLong.status());
        assertEquals(0, countSaved());
    }

    private int countSaved() {
        return jdbc.queryForObject("SELECT count(*) FROM concerts WHERE title LIKE ?", Integer.class,
                TITLE_PREFIX + "%");
    }

    private Response post(Long userId, String role, String body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/admin/concerts"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (userId != null) request.header("X-User-Id", Long.toString(userId)).header("X-User-Role", role);
        var response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        return new Response(response.statusCode(), response.body());
    }

    private record Response(int status, String body) {
        JsonNode json() {
            return JsonMapper.builder().build().readTree(body);
        }
    }
}
