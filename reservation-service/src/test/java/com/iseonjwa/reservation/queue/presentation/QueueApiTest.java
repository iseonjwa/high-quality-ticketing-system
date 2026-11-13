package com.iseonjwa.reservation.queue.presentation;

import com.iseonjwa.common.auth.EntryTokenDecoder;
import com.iseonjwa.reservation.queue.application.QueueService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// 대기열 진입·순번 조회·토큰 발급 API를 실제 HTTP, Redis, PostgreSQL로 확인
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.config.import=", "spring.cloud.config.enabled=false", "eureka.client.enabled=false"})
@ActiveProfiles("test")
class QueueApiTest {

    static final long USER_ID = 42L;
    static final long NO_SCHEDULE_ID = Long.MAX_VALUE; // DB에 없는 회차

    // 판매 중 / 판매 전 / 공연 시작됨 회차를 만들 때 쓰는 시각(SQL)
    static final String ON_SALE_OPEN_AT = "now() - interval '1 minute'";
    static final String ON_SALE_STARTS_AT = "now() + interval '1 day'";
    static final String NOT_OPEN_OPEN_AT = "now() + interval '1 hour'";
    static final String STARTED_OPEN_AT = "now() - interval '2 hour'";
    static final String STARTED_STARTS_AT = "now() - interval '1 minute'";

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final HttpClient http = HttpClient.newHttpClient();
    private final List<Long> createdVenueIds = new ArrayList<>();
    private final List<Long> createdScheduleIds = new ArrayList<>();

    @LocalServerPort
    int port;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    StringRedisTemplate stringRedisTemplate;
    @Autowired
    QueueService queueService;
    @Value("${entry-token.secret}")
    String entryTokenSecret;


    @AfterEach
    void cleanUp() {
        for (var scheduleId : createdScheduleIds) {
            stringRedisTemplate.delete(List.of(
                    key(scheduleId, "seq"), key(scheduleId, "waiting"), key(scheduleId, "admitted"),
                    key(scheduleId, "issued"), key(scheduleId, "lastTick")));
        }
        for (var venueId : createdVenueIds) {
            jdbc.update("DELETE FROM schedules WHERE venue_id = ?", venueId);
            jdbc.update("DELETE FROM venues WHERE id = ?", venueId);
        }
        jdbc.update("DELETE FROM concerts WHERE title = 'ISE-100 대기열 테스트' AND id NOT IN (SELECT concert_id FROM schedules)");
    }


    @Test
    void 사용자_헤더가_없으면_401() throws Exception {
        long scheduleId = createOnSaleSchedule();

        Response response = send(request(queuePath(scheduleId)).POST(HttpRequest.BodyPublishers.noBody()));

        assertFailure(response, 401, "UNAUTHORIZED");
    }

    @Nested
    class 진입 {

        @Test
        void 처음_진입하면_201과_순번_조회_주소를_준다() throws Exception {
            long scheduleId = createOnSaleSchedule();

            Response response = enter(USER_ID, scheduleId);

            assertEquals(201, response.status(), response.json().toString());
            assertEquals(queuePath(scheduleId) + "/me", response.location());
            JsonNode data = response.json().get("data");
            assertEquals("WAITING", data.get("status").asString());
            assertEquals(1L, data.get("position").asLong());
            assertTrue(data.has("estimatedWaitSeconds"), data.toString());
            assertTrue(data.has("nextPollAfterMillis"), data.toString());
        }

        @Test
        void 다시_진입하면_200과_같은_순번() throws Exception {
            long scheduleId = createOnSaleSchedule();
            enter(USER_ID, scheduleId);

            Response response = enter(USER_ID, scheduleId);

            assertEquals(200, response.status(), response.json().toString());
            assertEquals("WAITING", response.json().get("data").get("status").asString());
            assertEquals(1L, response.json().get("data").get("position").asLong());
        }

        @Test
        void 판매_전이면_409_SALE_NOT_OPEN() throws Exception {
            long scheduleId = createSchedule(NOT_OPEN_OPEN_AT, ON_SALE_STARTS_AT);

            assertFailure(enter(USER_ID, scheduleId), 409, "SALE_NOT_OPEN");
        }

        @Test
        void 공연이_시작됐으면_409_SALE_NOT_OPEN이고_키를_만들지_않는다() throws Exception {
            long scheduleId = createSchedule(STARTED_OPEN_AT, STARTED_STARTS_AT);

            assertFailure(enter(USER_ID, scheduleId), 409, "SALE_NOT_OPEN");
            assertFalse(stringRedisTemplate.hasKey(key(scheduleId, "seq")));
            assertFalse(stringRedisTemplate.hasKey(key(scheduleId, "waiting")));
        }

        @Test
        void 없는_회차면_404_NOT_FOUND() throws Exception {
            assertFailure(enter(USER_ID, NO_SCHEDULE_ID), 404, "NOT_FOUND");
        }
    }

    @Nested
    class 순번_조회 {

        @Test
        void 진입_후_조회하면_200_WAITING과_진입과_같은_순번() throws Exception {
            long scheduleId = createOnSaleSchedule();
            enter(7L, scheduleId);
            long entered = enter(USER_ID, scheduleId).json().get("data").get("position").asLong();

            Response response = position(USER_ID, scheduleId);

            assertEquals(200, response.status(), response.json().toString());
            assertEquals("WAITING", response.json().get("data").get("status").asString());
            assertEquals(2L, entered);
            assertEquals(entered, response.json().get("data").get("position").asLong());
        }

        @Test
        void 진입하지_않았으면_404_QUEUE_NOT_JOINED() throws Exception {
            long scheduleId = createOnSaleSchedule();

            assertFailure(position(USER_ID, scheduleId), 404, "QUEUE_NOT_JOINED");
        }

        @Test
        void 토큰_기록이_만료됐으면_404_QUEUE_NOT_JOINED() throws Exception {
            long scheduleId = createOnSaleSchedule();
            long elevenMinutesAgo = System.currentTimeMillis() - Duration.ofMinutes(11).toMillis();
            stringRedisTemplate.opsForZSet().add(key(scheduleId, "issued"), String.valueOf(USER_ID), elevenMinutesAgo);

            assertFailure(position(USER_ID, scheduleId), 404, "QUEUE_NOT_JOINED");
        }

        @Test
        void 토큰을_받은_사용자는_200_READY이고_status만_있다() throws Exception {
            long scheduleId = createOnSaleSchedule();
            enter(USER_ID, scheduleId);
            admitUntil(scheduleId, 1);
            issueToken(USER_ID, scheduleId);

            Response response = position(USER_ID, scheduleId);

            assertEquals(200, response.status(), response.json().toString());
            JsonNode data = response.json().get("data");
            assertEquals("READY", data.get("status").asString());
            assertEquals(1, data.size(), data.toString());
        }
    }

    @Nested
    class 토큰_발급 {

        @Test
        void 차례가_된_사용자는_200_ADMITTED와_토큰을_받는다() throws Exception {
            long scheduleId = createOnSaleSchedule();
            enter(USER_ID, scheduleId);
            admitUntil(scheduleId, 1);

            Response response = issueToken(USER_ID, scheduleId);

            assertEquals(200, response.status(), response.json().toString());
            assertEquals("입장 토큰을 발급했습니다", response.json().get("message").asString());
            JsonNode data = response.json().get("data");
            assertEquals("ADMITTED", data.get("status").asString());
            assertFalse(data.get("entryToken").asString().isBlank());
            assertTrue(data.get("entryTokenExpiresAt").asString().endsWith("+09:00"), data.toString());
            // 만료는 발급 후 약 10분(초 단위로 내림하므로 최대 1초 짧다)
            Duration untilExpiry = Duration.between(Instant.now(), Instant.parse(data.get("entryTokenExpiresAt").asString()));
            assertTrue(untilExpiry.compareTo(Duration.ofMinutes(9)) > 0
                    && untilExpiry.compareTo(Duration.ofMinutes(10)) <= 0, untilExpiry.toString());
        }

        @Test
        void 다시_요청하면_같은_토큰() throws Exception {
            long scheduleId = createOnSaleSchedule();
            enter(USER_ID, scheduleId);
            admitUntil(scheduleId, 1);
            JsonNode first = issueToken(USER_ID, scheduleId).json().get("data");

            Response response = issueToken(USER_ID, scheduleId);

            assertEquals(200, response.status(), response.json().toString());
            JsonNode second = response.json().get("data");
            assertEquals(first.get("entryToken").asString(), second.get("entryToken").asString());
            assertEquals(first.get("entryTokenExpiresAt").asString(), second.get("entryTokenExpiresAt").asString());
        }

        // ISE-207 완료 기준: 소모 뒤 대기열에 다시 진입하면 새 번호와 새 jti 토큰을 받는다
        @Test
        void 선점에_쓴_토큰을_정리하면_다시_줄을_서서_새_jti_토큰을_받는다() throws Exception {
            long scheduleId = createOnSaleSchedule();
            enter(USER_ID, scheduleId);
            admitUntil(scheduleId, 1);
            String usedJti = jti(issueToken(USER_ID, scheduleId));

            queueService.discardEntryToken(scheduleId, USER_ID, usedJti); // 선점 쪽이 트랜잭션 밖에서 부르는 것과 같다

            Response reentered = enter(USER_ID, scheduleId);
            assertEquals(201, reentered.status(), reentered.json().toString()); // 새로 줄을 선다
            assertEquals(2.0, stringRedisTemplate.opsForZSet().score(key(scheduleId, "waiting"), String.valueOf(USER_ID)));
            admitUntil(scheduleId, 2);
            String newJti = jti(issueToken(USER_ID, scheduleId));
            assertNotEquals(usedJti, newJti);
        }

        @Test
        void 다른_토큰의_jti로는_정리되지_않아_같은_토큰을_다시_받는다() throws Exception {
            long scheduleId = createOnSaleSchedule();
            enter(USER_ID, scheduleId);
            admitUntil(scheduleId, 1);
            String jti = jti(issueToken(USER_ID, scheduleId));

            queueService.discardEntryToken(scheduleId, USER_ID, "0".repeat(32));

            assertEquals(jti, jti(issueToken(USER_ID, scheduleId)));
        }

        @Test
        void 차례_전이면_409_QUEUE_NOT_READY() throws Exception {
            long scheduleId = createOnSaleSchedule();
            enter(USER_ID, scheduleId);

            assertFailure(issueToken(USER_ID, scheduleId), 409, "QUEUE_NOT_READY");
        }

        @Test
        void 진입하지_않았으면_404_QUEUE_NOT_JOINED() throws Exception {
            long scheduleId = createOnSaleSchedule();

            assertFailure(issueToken(USER_ID, scheduleId), 404, "QUEUE_NOT_JOINED");
        }
    }


    // ---- 데이터 준비 ----

    // openAt·startsAt은 SQL 식(예: "now() - interval '1 minute'")
    private long createSchedule(String openAt, String startsAt) {
        var venueId = jdbc.queryForObject("INSERT INTO venues (name, created_at, updated_at)"
                + " VALUES ('ISE-100 테스트 공연장', now(), now()) RETURNING id", Long.class);
        createdVenueIds.add(venueId);
        var concertId = jdbc.queryForObject("INSERT INTO concerts (title, version, created_at, updated_at)"
                + " VALUES ('ISE-100 대기열 테스트', 0, now(), now()) RETURNING id", Long.class);
        var scheduleId = jdbc.queryForObject("INSERT INTO schedules (concert_id, venue_id, starts_at, open_at, version,"
                + " created_at, updated_at) VALUES (?, ?, " + startsAt + ", " + openAt + ", 0, now(), now())"
                + " RETURNING id", Long.class, concertId, venueId);
        createdScheduleIds.add(scheduleId);
        return scheduleId;
    }

    private long createOnSaleSchedule() {
        return createSchedule(ON_SALE_OPEN_AT, ON_SALE_STARTS_AT);
    }

    // 테스트에서는 입장 스케줄러가 꺼져 있으므로 차례를 직접 만든다
    private void admitUntil(long scheduleId, long seq) {
        stringRedisTemplate.opsForValue().set(key(scheduleId, "admitted"), String.valueOf(seq));
    }

    private static String key(long scheduleId, String name) {
        return "queue:{" + scheduleId + "}:" + name;
    }


    // ---- API 호출 ----

    private Response enter(long userId, long scheduleId) throws Exception {
        return send(post(queuePath(scheduleId), userId));
    }

    private Response position(long userId, long scheduleId) throws Exception {
        return send(get(queuePath(scheduleId) + "/me", userId));
    }

    private Response issueToken(long userId, long scheduleId) throws Exception {
        return send(post(queuePath(scheduleId) + "/token", userId));
    }

    private String jti(Response tokenResponse) {
        assertEquals(200, tokenResponse.status(), tokenResponse.json().toString());
        String token = tokenResponse.json().get("data").get("entryToken").asString();
        return new EntryTokenDecoder(entryTokenSecret).decode(token).getId();
    }

    private static String queuePath(long scheduleId) {
        return "/api/v1/schedules/" + scheduleId + "/queue";
    }

    private HttpRequest.Builder post(String path, long userId) {
        return withUser(request(path).POST(HttpRequest.BodyPublishers.noBody()), userId);
    }

    private HttpRequest.Builder get(String path, long userId) {
        return withUser(request(path).GET(), userId);
    }

    // 사용자 헤더 없이 보낼 때(401) 직접 쓴다
    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
    }

    private static HttpRequest.Builder withUser(HttpRequest.Builder request, long userId) {
        return request
                .header("X-User-Id", String.valueOf(userId))
                .header("X-User-Role", "USER");
    }

    private Response send(HttpRequest.Builder request) throws Exception {
        var response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        return new Response(response.statusCode(), response.headers().firstValue("Location").orElse(null),
                mapper.readTree(response.body()));
    }


    // ---- 확인 ----

    private static void assertFailure(Response response, int status, String code) {
        assertEquals(status, response.status(), response.json().toString());
        assertEquals(code, response.json().get("code").asString(), response.json().toString());
        assertFalse(response.json().has("data"), response.json().toString());
    }

    private record Response(int status, String location, JsonNode json) {}
}
