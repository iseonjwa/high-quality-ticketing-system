package com.iseonjwa.reservation.lottery.presentation;

import com.iseonjwa.reservation.lottery.domain.policy.DrawEntrant;
import com.iseonjwa.reservation.lottery.domain.repository.LotteryEntryRepository;
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
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// 추첨 응모·취소·결과·공개 정보 API(details.md 2절, D3·D5). 추첨 행은 응모 기간을 자유롭게 정하려고 SQL로 직접 만든다.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.config.import=", "spring.cloud.config.enabled=false", "eureka.client.enabled=false"})
@ActiveProfiles("test")
class LotteryEntryApiTest {
    private static final String MARK = "추첨 응모 API 테스트";
    private static final long USER_ID = 7001L;

    private final HttpClient http = HttpClient.newHttpClient();

    @LocalServerPort
    int port;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    LotteryEntryRepository lotteryEntryRepository;

    private Long concertId;
    private Long venueId;
    private Long lotteryId;

    @BeforeEach
    void setUp() {
        concertId = jdbc.queryForObject("INSERT INTO concerts (title, version, created_at, updated_at)"
                + " VALUES (?, 0, now(), now()) RETURNING id", Long.class, MARK);
        venueId = jdbc.queryForObject("INSERT INTO venues (name, created_at, updated_at)"
                + " VALUES (?, now(), now()) RETURNING id", Long.class, MARK);
        lotteryId = insertLottery("now() - interval '1 hour'", "now() + interval '1 hour'", 2);
    }

    @AfterEach
    void cleanUp() {
        String lotteriesOfMark = "(SELECT l.id FROM lotteries l JOIN schedules s ON s.id = l.schedule_id"
                + " JOIN concerts c ON c.id = s.concert_id WHERE c.title = ?)";
        jdbc.update("DELETE FROM lottery_entries WHERE lottery_id IN " + lotteriesOfMark, MARK);
        jdbc.update("DELETE FROM lotteries WHERE id IN " + lotteriesOfMark, MARK);
        jdbc.update("DELETE FROM schedules WHERE concert_id IN (SELECT id FROM concerts WHERE title = ?)", MARK);
        jdbc.update("DELETE FROM concerts WHERE title = ?", MARK);
        jdbc.update("DELETE FROM venues WHERE name = ?", MARK);
    }

    @Test
    void 응모_기간_안이면_201이고_ENTERED로_저장한다() throws Exception {
        var response = enter(lotteryId, USER_ID, UUID.randomUUID(), 2);

        assertEquals(201, response.status());
        var data = response.json().get("data");
        assertEquals("ENTERED", data.get("status").asString());
        assertEquals(2, data.get("quantity").asInt());
        assertTrue(data.get("drawRank").isNull());
        assertEquals(1, countEntries(lotteryId));
    }

    @Test
    void 같은_멱등키로_다시_보내면_처음_응모를_그대로_돌려준다() throws Exception {
        var key = UUID.randomUUID();
        var first = enter(lotteryId, USER_ID, key, 2);

        var again = enter(lotteryId, USER_ID, key, 2);

        assertEquals(201, again.status());
        assertEquals(first.json().get("data").get("entryId").asLong(),
                again.json().get("data").get("entryId").asLong());
        assertEquals(1, countEntries(lotteryId));
    }

    @Test
    void 같은_멱등키로_매수를_바꿔_보내면_409다() throws Exception {
        var key = UUID.randomUUID();
        enter(lotteryId, USER_ID, key, 2);

        var changed = enter(lotteryId, USER_ID, key, 1);

        assertEquals(409, changed.status());
        assertEquals("IDEMPOTENCY_KEY_REUSED", changed.json().get("code").asString());
    }

    // 완료 기준: 같은 사용자의 동시 응모 2건은 1건만 성공한다. 경합이 우연히 비켜 가지 않게 사용자를 바꿔 5번 돌린다
    @Test
    void 같은_사용자가_다른_멱등키로_동시에_응모하면_1건만_성공한다() throws Exception {
        for (long userId = 8001; userId <= 8005; userId++) {
            var first = enterAsync(lotteryId, userId, UUID.randomUUID(), 1);
            var second = enterAsync(lotteryId, userId, UUID.randomUUID(), 1);
            var statuses = List.of(first.get().status(), second.get().status());

            assertTrue(statuses.contains(201) && statuses.contains(409), "userId=" + userId + " " + statuses);
            var rejected = first.get().status() == 409 ? first.get() : second.get();
            assertEquals("LOTTERY_ALREADY_ENTERED", rejected.json().get("code").asString());
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM lottery_entries WHERE lottery_id = ?"
                    + " AND user_id = ?", Integer.class, lotteryId, userId));
        }
    }

    @Test
    void 응모_시작_전이나_마감_뒤에는_409_LOTTERY_NOT_OPEN이다() throws Exception {
        Long notStarted = insertLottery("now() + interval '1 hour'", "now() + interval '2 hour'", 2);
        Long ended = insertLottery("now() - interval '2 hour'", "now() - interval '1 minute'", 2);

        var before = enter(notStarted, USER_ID, UUID.randomUUID(), 1);
        var after = enter(ended, USER_ID, UUID.randomUUID(), 1);

        assertEquals(409, before.status());
        assertEquals("LOTTERY_NOT_OPEN", before.json().get("code").asString());
        assertEquals(409, after.status());
        assertEquals("LOTTERY_NOT_OPEN", after.json().get("code").asString());
    }

    @Test
    void 추첨의_1인_최대_매수를_넘으면_400이다() throws Exception {
        Long onePerEntry = insertLottery("now() - interval '1 hour'", "now() + interval '1 hour'", 1);

        var response = enter(onePerEntry, USER_ID, UUID.randomUUID(), 2);

        assertEquals(400, response.status());
        assertEquals("VALIDATION_FAILED", response.json().get("code").asString());
        assertEquals(0, countEntries(onePerEntry));
    }

    @Test
    void 매수가_0이거나_3이거나_멱등키가_UUID가_아니면_400이다() throws Exception {
        var zero = enter(lotteryId, USER_ID, UUID.randomUUID(), 0);
        var three = enter(lotteryId, USER_ID, UUID.randomUUID(), 3);
        var badKey = send(HttpRequest.newBuilder(entriesUri(lotteryId))
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", "1-1-1-1-1")
                .header("X-User-Id", Long.toString(USER_ID))
                .header("X-User-Role", "USER")
                .POST(HttpRequest.BodyPublishers.ofString("{\"quantity\": 1}")));

        assertEquals(400, zero.status());
        assertEquals(400, three.status());
        assertEquals(400, badKey.status());
        assertEquals(0, countEntries(lotteryId));
    }

    @Test
    void 없는_추첨에_응모하면_404다() throws Exception {
        var response = enter(Long.MAX_VALUE, USER_ID, UUID.randomUUID(), 1);

        assertEquals(404, response.status());
    }

    // 완료 기준: 취소 뒤 재응모는 409다. 취소는 두 번 보내도 결과가 같다
    @Test
    void 취소하면_CANCELED가_되고_같은_추첨에_다시_응모할_수_없다() throws Exception {
        enter(lotteryId, USER_ID, UUID.randomUUID(), 2);

        var canceled = cancel(lotteryId, USER_ID);
        var canceledAgain = cancel(lotteryId, USER_ID);
        var reentry = enter(lotteryId, USER_ID, UUID.randomUUID(), 2);

        assertEquals(200, canceled.status());
        assertEquals("CANCELED", canceled.json().get("data").get("status").asString());
        assertEquals(200, canceledAgain.status());
        assertEquals("CANCELED", canceledAgain.json().get("data").get("status").asString());
        assertEquals(409, reentry.status());
        assertEquals("LOTTERY_ALREADY_ENTERED", reentry.json().get("code").asString());
    }

    // 완료 기준: 취소한 응모는 추첨 대상에서 빠진다
    @Test
    void 추첨_대상에는_취소한_응모가_빠진다() throws Exception {
        enter(lotteryId, 9001L, UUID.randomUUID(), 1);
        var kept = enter(lotteryId, 9002L, UUID.randomUUID(), 2);
        cancel(lotteryId, 9001L);

        var entrants = lotteryEntryRepository.findDrawEntrants(lotteryId);

        assertEquals(List.of(new DrawEntrant(kept.json().get("data").get("entryId").asLong(), 2)), entrants);
    }

    @Test
    void 마감_뒤에는_취소할_수_없다() throws Exception {
        enter(lotteryId, USER_ID, UUID.randomUUID(), 1);
        jdbc.update("UPDATE lotteries SET entry_end_at = now() - interval '1 minute',"
                + " entry_start_at = now() - interval '2 hour' WHERE id = ?", lotteryId);

        var response = cancel(lotteryId, USER_ID);

        assertEquals(409, response.status());
        assertEquals("LOTTERY_NOT_OPEN", response.json().get("code").asString());
        assertEquals("ENTERED", jdbc.queryForObject("SELECT status FROM lottery_entries WHERE lottery_id = ?",
                String.class, lotteryId));
    }

    @Test
    void 응모하지_않은_추첨의_취소와_결과_조회는_404다() throws Exception {
        assertEquals(404, cancel(lotteryId, USER_ID).status());
        assertEquals(404, myEntry(lotteryId, USER_ID).status());
    }

    @Test
    void 내_응모_결과를_조회한다() throws Exception {
        var entered = enter(lotteryId, USER_ID, UUID.randomUUID(), 2);
        jdbc.update("UPDATE lottery_entries SET status = 'WAITLISTED', draw_rank = 12 WHERE id = ?",
                entered.json().get("data").get("entryId").asLong());

        var response = myEntry(lotteryId, USER_ID);

        assertEquals(200, response.status());
        var data = response.json().get("data");
        assertEquals("WAITLISTED", data.get("status").asString());
        assertEquals(12, data.get("drawRank").asInt());
        assertTrue(data.get("reservationId").isNull());
    }

    @Test
    void 추첨_정보와_응모_규칙은_로그인_없이_조회한다() throws Exception {
        var before = Instant.now();
        var response = send(HttpRequest.newBuilder(URI.create(base() + "/api/v1/lotteries/" + lotteryId)).GET());

        assertEquals(200, response.status());
        var data = response.json().get("data");
        assertEquals("OPEN", data.get("status").asString());
        assertEquals(100, data.get("seatCount").asInt());
        var rules = data.get("rules");
        assertEquals(1, rules.get("minPerEntry").asInt());
        assertEquals(2, rules.get("maxPerEntry").asInt());
        assertEquals(false, rules.get("adjacentSeatsGuaranteed").asBoolean());
        assertEquals(false, rules.get("reentryAllowed").asBoolean());
        assertEquals(true, rules.get("billingKeyRequired").asBoolean());
        assertNull(data.get("seed"));
        assertServerTimeBetween(before, Instant.now(), data);
        assertEquals(404, send(HttpRequest.newBuilder(URI.create(base() + "/api/v1/lotteries/" + Long.MAX_VALUE))
                .GET()).status());
    }

    @Test
    void 회차로_추첨을_로그인_없이_찾고_추첨이_없거나_지운_회차는_404다() throws Exception {
        Long scheduleId = scheduleIdOf(lotteryId);
        Long deletedLotteryId = insertLottery("now() - interval '1 hour'", "now() + interval '1 hour'", 2);
        jdbc.update("UPDATE lotteries SET deleted_at = now() WHERE id = ?", deletedLotteryId);
        Long scheduleWithoutLottery = jdbc.queryForObject("INSERT INTO schedules (concert_id, venue_id, starts_at,"
                + " open_at, version, created_at, updated_at) VALUES (?, ?, now() + interval '30 day',"
                + " now() + interval '10 day', 0, now(), now()) RETURNING id", Long.class, concertId, venueId);

        var before = Instant.now();
        var found = lotteryOfSchedule(scheduleId);
        var after = Instant.now();
        var deleted = lotteryOfSchedule(scheduleIdOf(deletedLotteryId));
        var none = lotteryOfSchedule(scheduleWithoutLottery);

        assertEquals(200, found.status());
        var data = found.json().get("data");
        assertEquals(lotteryId, data.get("lotteryId").asLong());
        assertEquals(scheduleId, data.get("scheduleId").asLong());
        assertEquals("OPEN", data.get("status").asString());
        assertEquals(2, data.get("rules").get("maxPerEntry").asInt());
        assertNull(data.get("seed"));
        assertServerTimeBetween(before, after, data);
        assertEquals(404, deleted.status());
        assertEquals(404, none.status());
    }

    // 실제 Clock 빈으로 응답 시각을 만들었는지 요청 앞뒤 시각으로 본다(정확한 값은 LotteryInfoServerTimeTest)
    private static void assertServerTimeBetween(Instant before, Instant after, JsonNode data) {
        var serverTime = Instant.parse(data.get("serverTime").asString());
        assertFalse(serverTime.isBefore(before.truncatedTo(ChronoUnit.MILLIS)), serverTime + " < " + before);
        assertFalse(serverTime.isAfter(after), serverTime + " > " + after);
    }

    // 회차 하나에 추첨 하나라서(UNIQUE(schedule_id)) 추첨마다 회차를 새로 만든다
    private Long insertLottery(String entryStartSql, String entryEndSql, int maxPerEntry) {
        Long scheduleId = jdbc.queryForObject("INSERT INTO schedules (concert_id, venue_id, starts_at, open_at,"
                + " version, created_at, updated_at) VALUES (?, ?, now() + interval '30 day',"
                + " now() + interval '10 day', 0, now(), now()) RETURNING id", Long.class, concertId, venueId);
        return jdbc.queryForObject("INSERT INTO lotteries (schedule_id, source_grade, seat_count, waitlist_count,"
                + " max_per_entry, entry_start_at, entry_end_at, draw_at, seed, status, created_at, updated_at)"
                + " VALUES (?, 'S', 100, 50, ?, " + entryStartSql + ", " + entryEndSql + ","
                + " now() + interval '3 hour', ?, 'OPEN', now(), now()) RETURNING id",
                Long.class, scheduleId, maxPerEntry, new byte[32]);
    }

    private Long scheduleIdOf(Long lotteryId) {
        return jdbc.queryForObject("SELECT schedule_id FROM lotteries WHERE id = ?", Long.class, lotteryId);
    }

    private Response lotteryOfSchedule(Long scheduleId) throws Exception {
        return send(HttpRequest.newBuilder(URI.create(base() + "/api/v1/schedules/" + scheduleId + "/lottery"))
                .GET());
    }

    private int countEntries(Long lotteryId) {
        return jdbc.queryForObject("SELECT count(*) FROM lottery_entries WHERE lottery_id = ?", Integer.class,
                lotteryId);
    }

    private String base() {
        return "http://127.0.0.1:" + port;
    }

    private URI entriesUri(Long lotteryId) {
        return URI.create(base() + "/api/v1/lotteries/" + lotteryId + "/entries");
    }

    private HttpRequest.Builder enterRequest(Long lotteryId, Long userId, UUID key, int quantity) {
        return HttpRequest.newBuilder(entriesUri(lotteryId))
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", key.toString())
                .header("X-User-Id", Long.toString(userId))
                .header("X-User-Role", "USER")
                .POST(HttpRequest.BodyPublishers.ofString("{\"quantity\": " + quantity + "}"));
    }

    private Response enter(Long lotteryId, Long userId, UUID key, int quantity) throws Exception {
        return send(enterRequest(lotteryId, userId, key, quantity));
    }

    private CompletableFuture<Response> enterAsync(Long lotteryId, Long userId, UUID key, int quantity) {
        return http.sendAsync(enterRequest(lotteryId, userId, key, quantity).build(),
                        HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> new Response(response.statusCode(), response.body()));
    }

    private Response cancel(Long lotteryId, Long userId) throws Exception {
        return send(HttpRequest.newBuilder(URI.create(base() + "/api/v1/lotteries/" + lotteryId + "/entries/me"))
                .header("X-User-Id", Long.toString(userId))
                .header("X-User-Role", "USER")
                .DELETE());
    }

    private Response myEntry(Long lotteryId, Long userId) throws Exception {
        return send(HttpRequest.newBuilder(URI.create(base() + "/api/v1/lotteries/" + lotteryId + "/entries/me"))
                .header("X-User-Id", Long.toString(userId))
                .header("X-User-Role", "USER")
                .GET());
    }

    private Response send(HttpRequest.Builder request) throws Exception {
        var response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        return new Response(response.statusCode(), response.body());
    }

    private record Response(int status, String body) {
        JsonNode json() {
            return JsonMapper.builder().build().readTree(body);
        }
    }
}
