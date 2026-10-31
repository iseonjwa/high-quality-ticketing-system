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

// 운영자 회차 좌석 생성 API(SA 11-5). 성공은 시드 공연장(KSPO DOME, 15,000석)으로,
// 입력 검증은 VIP·R 좌석만 있는 작은 테스트 공연장으로 확인한다.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.config.import=", "spring.cloud.config.enabled=false", "eureka.client.enabled=false"})
@ActiveProfiles("test")
class AdminScheduleSeatApiTest {
    private static final long ADMIN_ID = 9003L;
    private static final String MARK = "회차 좌석 API 테스트";
    private static final String ALL_GRADES = """
            {"prices": [{"grade": "VIP", "price": 165000}, {"grade": "R", "price": 143000},
                        {"grade": "S", "price": 121000}]}""";
    private static final String VIP_AND_R = """
            {"prices": [{"grade": "VIP", "price": 165000}, {"grade": "R", "price": 143000}]}""";

    private final HttpClient http = HttpClient.newHttpClient();

    @LocalServerPort
    int port;
    @Autowired
    JdbcTemplate jdbc;

    private Long seedScheduleId;
    private Long smallScheduleId;

    @BeforeEach
    void setUp() {
        Long concertId = jdbc.queryForObject("INSERT INTO concerts (title, version, created_at, updated_at)"
                + " VALUES (?, 0, now(), now()) RETURNING id", Long.class, MARK);
        // 다른 테스트도 같은 이름의 공연장을 만들 수 있어, 좌석 15,000석이 있는 Flyway 시드 공연장을 고른다
        Long seedVenueId = jdbc.queryForObject("SELECT v.id FROM venues v JOIN venue_seats vs ON vs.venue_id = v.id"
                + " WHERE v.name = 'KSPO DOME' GROUP BY v.id HAVING count(*) = 15000 ORDER BY v.id LIMIT 1",
                Long.class);
        Long smallVenueId = jdbc.queryForObject("INSERT INTO venues (name, created_at, updated_at)"
                + " VALUES (?, now(), now()) RETURNING id", Long.class, MARK);
        jdbc.update("INSERT INTO venue_seats (venue_id, section, row_no, seat_no, grade, created_at, updated_at)"
                + " VALUES (?, 'A', 1, 1, 'VIP', now(), now()), (?, 'B', 1, 1, 'R', now(), now())",
                smallVenueId, smallVenueId);
        seedScheduleId = insertSchedule(concertId, seedVenueId);
        smallScheduleId = insertSchedule(concertId, smallVenueId);
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM schedule_seats WHERE schedule_id IN"
                + " (SELECT s.id FROM schedules s JOIN concerts c ON c.id = s.concert_id WHERE c.title = ?)", MARK);
        jdbc.update("DELETE FROM schedules WHERE concert_id IN (SELECT id FROM concerts WHERE title = ?)", MARK);
        jdbc.update("DELETE FROM concerts WHERE title = ?", MARK);
        jdbc.update("DELETE FROM venue_seats WHERE venue_id IN (SELECT id FROM venues WHERE name = ?)", MARK);
        jdbc.update("DELETE FROM venues WHERE name = ?", MARK);
    }

    @Test
    void 공연장_좌석_15000석을_등급별_가격으로_만든다() throws Exception {
        var response = post(seedScheduleId, ADMIN_ID, "ADMIN", ALL_GRADES);

        assertEquals(201, response.status());
        var data = response.json().get("data");
        assertEquals(seedScheduleId, data.get("scheduleId").asLong());
        assertEquals(15_000, data.get("createdSeats").asInt());

        assertEquals(3_000, countSeats(seedScheduleId, "VIP", 165_000));
        assertEquals(6_000, countSeats(seedScheduleId, "R", 143_000));
        assertEquals(6_000, countSeats(seedScheduleId, "S", 121_000));
        assertEquals(15_000, jdbc.queryForObject("SELECT count(*) FROM schedule_seats"
                + " WHERE schedule_id = ? AND status = 'AVAILABLE' AND created_by = ?",
                Integer.class, seedScheduleId, ADMIN_ID));
    }

    @Test
    void 이미_좌석을_만든_회차는_409이고_좌석이_늘지_않는다() throws Exception {
        post(smallScheduleId, ADMIN_ID, "ADMIN", VIP_AND_R);

        var again = post(smallScheduleId, ADMIN_ID, "ADMIN", VIP_AND_R);

        assertEquals(409, again.status());
        assertEquals("SCHEDULE_SEATS_ALREADY_CREATED", again.json().get("code").asString());
        assertEquals(2, countAll(smallScheduleId));
    }

    @Test
    void 없는_회차는_404다() throws Exception {
        var response = post(Long.MAX_VALUE, ADMIN_ID, "ADMIN", VIP_AND_R);

        assertEquals(404, response.status());
    }

    @Test
    void 공연장에_있는_등급의_가격이_빠지면_400이다() throws Exception {
        var response = post(smallScheduleId, ADMIN_ID, "ADMIN", """
                {"prices": [{"grade": "VIP", "price": 165000}]}""");

        assertEquals(400, response.status());
        assertEquals("VALIDATION_FAILED", response.json().get("code").asString());
        assertEquals(0, countAll(smallScheduleId));
    }

    @Test
    void 공연장에_없는_등급의_가격이_오면_400이다() throws Exception {
        var response = post(smallScheduleId, ADMIN_ID, "ADMIN", ALL_GRADES);

        assertEquals(400, response.status());
        assertEquals(0, countAll(smallScheduleId));
    }

    @Test
    void 같은_등급이_두_번_오면_400이다() throws Exception {
        var response = post(smallScheduleId, ADMIN_ID, "ADMIN", """
                {"prices": [{"grade": "VIP", "price": 165000}, {"grade": "VIP", "price": 100000},
                            {"grade": "R", "price": 143000}]}""");

        assertEquals(400, response.status());
        assertEquals(0, countAll(smallScheduleId));
    }

    @Test
    void 가격이_0이거나_목록이_비었거나_없는_등급이면_400이다() throws Exception {
        var zeroPrice = post(smallScheduleId, ADMIN_ID, "ADMIN", """
                {"prices": [{"grade": "VIP", "price": 0}, {"grade": "R", "price": 143000}]}""");
        var empty = post(smallScheduleId, ADMIN_ID, "ADMIN", """
                {"prices": []}""");
        var unknownGrade = post(smallScheduleId, ADMIN_ID, "ADMIN", """
                {"prices": [{"grade": "VVIP", "price": 165000}, {"grade": "R", "price": 143000}]}""");

        assertEquals(400, zeroPrice.status());
        assertEquals(400, empty.status());
        assertEquals(400, unknownGrade.status());
        assertEquals(0, countAll(smallScheduleId));
    }

    @Test
    void 일반_사용자는_403이다() throws Exception {
        var response = post(smallScheduleId, 1L, "USER", VIP_AND_R);

        assertEquals(403, response.status());
        assertEquals(0, countAll(smallScheduleId));
    }

    private Long insertSchedule(Long concertId, Long venueId) {
        return jdbc.queryForObject("INSERT INTO schedules (concert_id, venue_id, starts_at, open_at, version,"
                + " created_at, updated_at) VALUES (?, ?, now() + interval '30 day', now() + interval '10 day', 0,"
                + " now(), now()) RETURNING id", Long.class, concertId, venueId);
    }

    private int countSeats(Long scheduleId, String grade, long price) {
        return jdbc.queryForObject("SELECT count(*) FROM schedule_seats WHERE schedule_id = ? AND grade = ?"
                + " AND price = ?", Integer.class, scheduleId, grade, price);
    }

    private int countAll(Long scheduleId) {
        return jdbc.queryForObject("SELECT count(*) FROM schedule_seats WHERE schedule_id = ?", Integer.class,
                scheduleId);
    }

    private Response post(Long scheduleId, Long userId, String role, String body) throws Exception {
        var uri = URI.create("http://127.0.0.1:" + port + "/api/v1/admin/schedules/" + scheduleId + "/seats");
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
