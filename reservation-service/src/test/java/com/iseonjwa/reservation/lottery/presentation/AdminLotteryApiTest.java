package com.iseonjwa.reservation.lottery.presentation;

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
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// 운영자 추첨 설정 API(details.md 2절, D9). S석 3개·VIP석 1개인 작은 공연장으로 확인한다.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.config.import=", "spring.cloud.config.enabled=false", "eureka.client.enabled=false"})
@ActiveProfiles("test")
class AdminLotteryApiTest {
    private static final long ADMIN_ID = 9004L;
    private static final String MARK = "추첨 설정 API 테스트";
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private final HttpClient http = HttpClient.newHttpClient();

    @LocalServerPort
    int port;
    @Autowired
    JdbcTemplate jdbc;

    private Long concertId;
    private Long venueId;
    private Long scheduleId;
    private List<Long> sSeatIds;

    @BeforeEach
    void setUp() {
        concertId = jdbc.queryForObject("INSERT INTO concerts (title, version, created_at, updated_at)"
                + " VALUES (?, 0, now(), now()) RETURNING id", Long.class, MARK);
        venueId = jdbc.queryForObject("INSERT INTO venues (name, created_at, updated_at)"
                + " VALUES (?, now(), now()) RETURNING id", Long.class, MARK);
        jdbc.update("INSERT INTO venue_seats (venue_id, section, row_no, seat_no, grade, created_at, updated_at)"
                + " VALUES (?, 'A', 1, 1, 'VIP', now(), now()), (?, 'B', 1, 1, 'S', now(), now()),"
                + " (?, 'B', 1, 2, 'S', now(), now()), (?, 'B', 1, 3, 'S', now(), now())",
                venueId, venueId, venueId, venueId);
        scheduleId = insertSchedule("now() + interval '10 day'");
        sSeatIds = jdbc.queryForList("SELECT id FROM schedule_seats WHERE schedule_id = ? AND grade = 'S'"
                + " ORDER BY id", Long.class, scheduleId);
    }

    @AfterEach
    void cleanUp() {
        String schedulesOfMark = "(SELECT s.id FROM schedules s JOIN concerts c ON c.id = s.concert_id"
                + " WHERE c.title = ?)";
        jdbc.update("DELETE FROM lotteries WHERE schedule_id IN " + schedulesOfMark, MARK);
        jdbc.update("DELETE FROM schedule_seats WHERE schedule_id IN " + schedulesOfMark, MARK);
        jdbc.update("DELETE FROM schedules WHERE concert_id IN (SELECT id FROM concerts WHERE title = ?)", MARK);
        jdbc.update("DELETE FROM concerts WHERE title = ?", MARK);
        jdbc.update("DELETE FROM venue_seats WHERE venue_id IN (SELECT id FROM venues WHERE name = ?)", MARK);
        jdbc.update("DELETE FROM venues WHERE name = ?", MARK);
    }

    @Test
    void S석_2개를_ID_순으로_LOTTERY로_떼고_추첨을_OPEN으로_만든다() throws Exception {
        var response = post(scheduleId, ADMIN_ID, "ADMIN", body("S", 2, drawAtSeoul(LocalTime.of(4, 0))));

        assertEquals(201, response.status());
        var data = response.json().get("data");
        assertEquals(scheduleId, data.get("scheduleId").asLong());
        assertEquals("S", data.get("sourceGrade").asString());
        assertEquals(2, data.get("seatCount").asInt());
        assertEquals("OPEN", data.get("status").asString());
        assertEquals(0, data.get("warnings").size());

        assertEquals(List.of(sSeatIds.get(0), sSeatIds.get(1)), seatIdsOf("LOTTERY"));
        assertEquals(List.of(sSeatIds.get(2)), seatIdsOf("S"));
        assertEquals(1, seatIdsOf("VIP").size());
        assertEquals(32, jdbc.queryForObject("SELECT octet_length(seed) FROM lotteries WHERE id = ?",
                Integer.class, data.get("lotteryId").asLong()));
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM schedule_seats WHERE schedule_id = ?"
                + " AND grade = 'LOTTERY' AND updated_by = ?", Integer.class, scheduleId, ADMIN_ID));
    }

    // 막지 않는 운영 정책이라 201이고 경고만 담는다(D10)
    @Test
    void 추첨_시각이_저트래픽_시간대_밖이면_201에_경고를_담는다() throws Exception {
        var response = post(scheduleId, ADMIN_ID, "ADMIN", body("S", 1, drawAtSeoul(LocalTime.of(12, 0))));

        assertEquals(201, response.status());
        var warnings = response.json().get("data").get("warnings");
        assertEquals(1, warnings.size());
        assertEquals("DRAW_AT_OUTSIDE_LOW_TRAFFIC_WINDOW", warnings.get(0).asString());
    }

    @Test
    void AVAILABLE_좌석이_모자라면_409이고_좌석과_추첨이_바뀌지_않는다() throws Exception {
        jdbc.update("UPDATE schedule_seats SET status = 'HELD' WHERE id = ?", sSeatIds.get(0));

        var response = post(scheduleId, ADMIN_ID, "ADMIN", body("S", 3, drawAtSeoul(LocalTime.of(4, 0))));

        assertEquals(409, response.status());
        assertEquals("LOTTERY_SEATS_NOT_ENOUGH", response.json().get("code").asString());
        assertEquals(List.of(), seatIdsOf("LOTTERY"));
        assertEquals(sSeatIds, seatIdsOf("S"));
        assertEquals(0, countLotteries(scheduleId));
    }

    @Test
    void 같은_회차에_두_번째_추첨을_설정하면_409이고_좌석이_더_바뀌지_않는다() throws Exception {
        post(scheduleId, ADMIN_ID, "ADMIN", body("S", 1, drawAtSeoul(LocalTime.of(4, 0))));

        var again = post(scheduleId, ADMIN_ID, "ADMIN", body("S", 1, drawAtSeoul(LocalTime.of(4, 0))));

        assertEquals(409, again.status());
        assertEquals("LOTTERY_ALREADY_EXISTS", again.json().get("code").asString());
        assertEquals(1, seatIdsOf("LOTTERY").size());
        assertEquals(1, countLotteries(scheduleId));
    }

    @Test
    void 일반_오픈이_지난_회차는_409다() throws Exception {
        Long openedScheduleId = insertSchedule("now() - interval '1 hour'");

        var response = post(openedScheduleId, ADMIN_ID, "ADMIN", body("S", 1, drawAtSeoul(LocalTime.of(4, 0))));

        assertEquals(409, response.status());
        assertEquals("LOTTERY_SETUP_CLOSED", response.json().get("code").asString());
        assertEquals(0, countLotteries(openedScheduleId));
    }

    @Test
    void 응모_시작이_지났으면_409다() throws Exception {
        var now = Instant.now();
        var response = post(scheduleId, ADMIN_ID, "ADMIN", body("S", 1, now.minus(Duration.ofHours(1)),
                now.plus(Duration.ofDays(1)), drawAtSeoul(LocalTime.of(4, 0))));

        assertEquals(409, response.status());
        assertEquals("LOTTERY_SETUP_CLOSED", response.json().get("code").asString());
    }

    @Test
    void LOTTERY_등급에서_떼거나_응모_마감이_추첨보다_늦으면_400이다() throws Exception {
        var drawAt = drawAtSeoul(LocalTime.of(4, 0));
        var fromLottery = post(scheduleId, ADMIN_ID, "ADMIN", body("LOTTERY", 1, drawAt));
        var endAfterDraw = post(scheduleId, ADMIN_ID, "ADMIN", body("S", 1, entryStart(), drawAt.plusSeconds(1),
                drawAt));

        assertEquals(400, fromLottery.status());
        assertEquals("VALIDATION_FAILED", fromLottery.json().get("code").asString());
        assertEquals(400, endAfterDraw.status());
        assertEquals(0, countLotteries(scheduleId));
    }

    // 추첨 뒤 결제·승계·종결에 최장 60분이 걸리므로(D14) 공연 시작 60분 전까지만 추첨할 수 있다
    @Test
    void 추첨_시각은_공연_시작_60분_전까지만_허용한다() throws Exception {
        var startsAt = jdbc.queryForObject("SELECT starts_at FROM schedules WHERE id = ?", java.sql.Timestamp.class,
                scheduleId).toInstant();

        var tooLate = post(scheduleId, ADMIN_ID, "ADMIN", body("S", 1,
                startsAt.minus(Duration.ofMinutes(59))));
        var justInTime = post(scheduleId, ADMIN_ID, "ADMIN", body("S", 1,
                startsAt.minus(Duration.ofMinutes(60))));

        assertEquals(400, tooLate.status());
        assertEquals("VALIDATION_FAILED", tooLate.json().get("code").asString());
        assertEquals(201, justInTime.status());
    }

    @Test
    void 좌석_수가_0이거나_1인_매수가_3이면_400이다() throws Exception {
        var zeroSeats = post(scheduleId, ADMIN_ID, "ADMIN", body("S", 0, drawAtSeoul(LocalTime.of(4, 0))));
        var threePerEntry = post(scheduleId, ADMIN_ID, "ADMIN", body("S", 1, entryStart(), entryEnd(),
                drawAtSeoul(LocalTime.of(4, 0))).replace("\"maxPerEntry\": 2", "\"maxPerEntry\": 3"));

        assertEquals(400, zeroSeats.status());
        assertEquals(400, threePerEntry.status());
        assertEquals(0, countLotteries(scheduleId));
    }

    @Test
    void 없는_회차는_404다() throws Exception {
        var response = post(Long.MAX_VALUE, ADMIN_ID, "ADMIN", body("S", 1, drawAtSeoul(LocalTime.of(4, 0))));

        assertEquals(404, response.status());
    }

    @Test
    void 일반_사용자는_403이다() throws Exception {
        var response = post(scheduleId, 1L, "USER", body("S", 1, drawAtSeoul(LocalTime.of(4, 0))));

        assertEquals(403, response.status());
        assertTrue(seatIdsOf("LOTTERY").isEmpty());
    }

    private Long insertSchedule(String openAtSql) {
        Long id = jdbc.queryForObject("INSERT INTO schedules (concert_id, venue_id, starts_at, open_at, version,"
                + " created_at, updated_at) VALUES (?, ?, now() + interval '30 day', " + openAtSql + ", 0,"
                + " now(), now()) RETURNING id", Long.class, concertId, venueId);
        jdbc.update("INSERT INTO schedule_seats (schedule_id, venue_seat_id, section, grade, price, status,"
                + " created_at, updated_at) SELECT ?, vs.id, vs.section, vs.grade, 121000, 'AVAILABLE', now(), now()"
                + " FROM venue_seats vs WHERE vs.venue_id = ? ORDER BY vs.id", id, venueId);
        return id;
    }

    // 응모는 내일부터 모레까지, 추첨은 사흘 뒤 서울 시각으로 정한다
    private static Instant entryStart() {
        return Instant.now().plus(Duration.ofDays(1));
    }

    private static Instant entryEnd() {
        return Instant.now().plus(Duration.ofDays(2));
    }

    private static Instant drawAtSeoul(LocalTime time) {
        return LocalDate.now(SEOUL).plusDays(3).atTime(time).atZone(SEOUL).toInstant();
    }

    private static String body(String grade, int seatCount, Instant drawAt) {
        return body(grade, seatCount, entryStart(), entryEnd(), drawAt);
    }

    private static String body(String grade, int seatCount, Instant entryStartAt, Instant entryEndAt,
                               Instant drawAt) {
        return """
                {"sourceGrade": "%s", "seatCount": %d, "waitlistCount": 1, "maxPerEntry": 2,
                 "entryStartAt": "%s", "entryEndAt": "%s", "drawAt": "%s"}"""
                .formatted(grade, seatCount, entryStartAt, entryEndAt, drawAt);
    }

    private List<Long> seatIdsOf(String grade) {
        return jdbc.queryForList("SELECT id FROM schedule_seats WHERE schedule_id = ? AND grade = ? ORDER BY id",
                Long.class, scheduleId, grade);
    }

    private int countLotteries(Long scheduleId) {
        return jdbc.queryForObject("SELECT count(*) FROM lotteries WHERE schedule_id = ?", Integer.class,
                scheduleId);
    }

    private Response post(Long scheduleId, Long userId, String role, String body) throws Exception {
        var uri = URI.create("http://127.0.0.1:" + port + "/api/v1/admin/schedules/" + scheduleId + "/lotteries");
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
