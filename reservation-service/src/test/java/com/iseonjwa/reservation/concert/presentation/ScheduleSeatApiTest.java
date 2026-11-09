package com.iseonjwa.reservation.concert.presentation;

import com.iseonjwa.reservation.concert.application.ScheduleSeatQueryService;
import com.iseonjwa.reservation.concert.presentation.dto.response.ScheduleSeatsResponse;
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
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// 회차 좌석 조회 API(ISE-148). 로그인·입장 토큰은 Gateway가 보므로 여기서는 헤더 없이 부른다.
// 공연장: A구역 VIP 2석(1열 1·2번), B구역 S 3석(1열 2·1번, 2열 1번). B구역에 선점된 좌석과 추첨석을 하나씩 둔다.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.config.import=", "spring.cloud.config.enabled=false", "eureka.client.enabled=false"})
@ActiveProfiles("test")
class ScheduleSeatApiTest {
    private static final String MARK = "좌석 조회 API 테스트";

    private final HttpClient http = HttpClient.newHttpClient();

    @LocalServerPort
    int port;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    ScheduleSeatQueryService service;
    @Autowired
    JsonMapper jsonMapper;

    private Long venueId;
    private Long scheduleId;

    @BeforeEach
    void setUp() {
        venueId = jdbc.queryForObject("INSERT INTO venues (name, created_at, updated_at)"
                + " VALUES (?, now(), now()) RETURNING id", Long.class, MARK);
        // 일부러 열·번호 순서와 다르게 넣어 응답 정렬을 확인한다
        jdbc.update("INSERT INTO venue_seats (venue_id, section, row_no, seat_no, grade, created_at, updated_at)"
                + " VALUES (?, 'B', 2, 1, 'S', now(), now()), (?, 'B', 1, 2, 'S', now(), now()),"
                + " (?, 'B', 1, 1, 'S', now(), now()), (?, 'A', 1, 2, 'VIP', now(), now()),"
                + " (?, 'A', 1, 1, 'VIP', now(), now())", venueId, venueId, venueId, venueId, venueId);
        Long concertId = jdbc.queryForObject("INSERT INTO concerts (title, version, created_at, updated_at)"
                + " VALUES (?, 0, now(), now()) RETURNING id", Long.class, MARK);
        scheduleId = jdbc.queryForObject("INSERT INTO schedules (concert_id, venue_id, starts_at, open_at, version,"
                + " created_at, updated_at) VALUES (?, ?, now() + interval '30 day', now() - interval '1 day', 0,"
                + " now(), now()) RETURNING id", Long.class, concertId, venueId);
        jdbc.update("INSERT INTO schedule_seats (schedule_id, venue_seat_id, section, grade, price, status,"
                + " created_at, updated_at) SELECT ?, vs.id, vs.section, vs.grade,"
                + " CASE vs.grade WHEN 'VIP' THEN 165000 ELSE 121000 END, 'AVAILABLE', now(), now()"
                + " FROM venue_seats vs WHERE vs.venue_id = ?", scheduleId, venueId);
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM schedule_seats WHERE schedule_id = ?", scheduleId);
        jdbc.update("DELETE FROM schedules WHERE venue_id = ?", venueId);
        jdbc.update("DELETE FROM concerts WHERE title = ?", MARK);
        jdbc.update("DELETE FROM venue_seats WHERE venue_id = ?", venueId);
        jdbc.update("DELETE FROM venues WHERE id = ?", venueId);
    }

    @Test
    void 좌석을_구역별로_열과_번호_순으로_묶어_준다() throws Exception {
        var response = get("/api/v1/schedules/" + scheduleId + "/seats");

        assertEquals(200, response.status());
        var data = response.json().get("data");
        assertEquals(scheduleId, data.get("scheduleId").asLong());
        assertFalse(data.get("serverTime").isNull());

        var sections = data.get("sections");
        assertEquals(2, sections.size());
        var a = sections.get(0);
        assertEquals("A", a.get("section").asString());
        assertEquals("VIP", a.get("grade").asString());
        assertEquals(165000, a.get("price").asLong());
        assertEquals(List.of("1-1", "1-2"), rowAndNumbers(a));

        var b = sections.get(1);
        assertEquals("B", b.get("section").asString());
        assertEquals("S", b.get("grade").asString());
        assertEquals(121000, b.get("price").asLong());
        assertEquals(List.of("1-1", "1-2", "2-1"), rowAndNumbers(b));
        b.get("seats").forEach(seat -> assertTrue(seat.get("available").asBoolean()));
    }

    @Test
    void 선점된_좌석과_추첨석은_고를_수_없고_구역_등급은_원래_등급이다() throws Exception {
        jdbc.update("UPDATE schedule_seats SET status = 'HELD' WHERE id = ?", seatIdOf("B", 1, 1));
        jdbc.update("UPDATE schedule_seats SET grade = 'LOTTERY' WHERE id = ?", seatIdOf("B", 1, 2));

        var b = get("/api/v1/schedules/" + scheduleId + "/seats").json().get("data").get("sections").get(1);

        assertEquals("S", b.get("grade").asString());
        var seats = b.get("seats");
        assertFalse(seats.get(0).get("available").asBoolean());
        assertFalse(seats.get(1).get("available").asBoolean());
        assertTrue(seats.get(2).get("available").asBoolean());
    }

    @Test
    void 좌석을_아직_만들지_않은_회차는_구역이_비어_있다() throws Exception {
        jdbc.update("DELETE FROM schedule_seats WHERE schedule_id = ?", scheduleId);

        var response = get("/api/v1/schedules/" + scheduleId + "/seats");

        assertEquals(200, response.status());
        assertEquals(0, response.json().get("data").get("sections").size());
    }

    @Test
    void 없거나_지운_회차는_404다() throws Exception {
        assertEquals(404, get("/api/v1/schedules/" + Long.MAX_VALUE + "/seats").status());

        jdbc.update("UPDATE schedules SET deleted_at = now() WHERE id = ?", scheduleId);

        assertEquals(404, get("/api/v1/schedules/" + scheduleId + "/seats").status());
    }

    // P4a(측정 브랜치): 행으로 만든 응답이 기존 엔티티 경로와 글자 하나까지 같아야 한다.
    // k6 K1은 응답 문자열에서 "available":true를 찾으므로 모양이 바뀌면 측정이 달라진다
    @Test
    void 행으로_만든_응답이_엔티티_경로와_JSON이_같다() {
        jdbc.update("UPDATE schedule_seats SET status = 'HELD' WHERE id = ?", seatIdOf("B", 1, 1));
        jdbc.update("UPDATE schedule_seats SET grade = 'LOTTERY' WHERE id = ?", seatIdOf("B", 1, 2));
        var now = Instant.parse("2026-11-07T00:00:00Z");

        var expected = ScheduleSeatsResponse.of(scheduleId, now, service.getSeats(scheduleId));
        var actual = ScheduleSeatsResponse.fromRows(scheduleId, now, service.getSeatMap(scheduleId));

        assertEquals(jsonMapper.writeValueAsString(expected), jsonMapper.writeValueAsString(actual));
    }

    // P4c(측정 브랜치): 좌석 상태는 1초 동안 같은 값을 쓰고, 지나면 다시 읽는다
    @Test
    void 상태는_1초_동안_같은_값을_쓰고_지나면_다시_읽는다() throws Exception {
        assertTrue(firstSeatOfB().get("available").asBoolean());

        jdbc.update("UPDATE schedule_seats SET status = 'HELD' WHERE id = ?", seatIdOf("B", 1, 1));

        // Note: P4c의 트레이드오프다. 1초 안에는 이미 잡힌 좌석이 고를 수 있는 것으로 보인다
        assertTrue(firstSeatOfB().get("available").asBoolean());
        Thread.sleep(1_200);
        assertFalse(firstSeatOfB().get("available").asBoolean());
    }

    @Test
    void 캐시한_뒤_지운_회차는_1초가_지나면_404다() throws Exception {
        assertEquals(200, get("/api/v1/schedules/" + scheduleId + "/seats").status());

        jdbc.update("UPDATE schedules SET deleted_at = now() WHERE id = ?", scheduleId);

        assertEquals(200, get("/api/v1/schedules/" + scheduleId + "/seats").status());
        Thread.sleep(1_200);
        assertEquals(404, get("/api/v1/schedules/" + scheduleId + "/seats").status());
    }

    private JsonNode firstSeatOfB() throws Exception {
        return get("/api/v1/schedules/" + scheduleId + "/seats").json()
                .get("data").get("sections").get(1).get("seats").get(0);
    }

    private Long seatIdOf(String section, int rowNo, int seatNo) {
        return jdbc.queryForObject("SELECT ss.id FROM schedule_seats ss JOIN venue_seats vs ON vs.id = ss.venue_seat_id"
                + " WHERE ss.schedule_id = ? AND vs.section = ? AND vs.row_no = ? AND vs.seat_no = ?", Long.class,
                scheduleId, section, rowNo, seatNo);
    }

    private static List<String> rowAndNumbers(JsonNode section) {
        var result = new ArrayList<String>();
        section.get("seats").forEach(seat -> result.add(seat.get("row").asInt() + "-" + seat.get("number").asInt()));
        return result;
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
