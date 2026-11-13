package com.iseonjwa.reservation.seathold.presentation;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.distribution.CountAtBucket;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// 좌석 선점 결과 카운터와 선점·조회 API의 HTTP 지연 히스토그램이 대시보드(infra/grafana/dashboards/seathold.json)가 읽는 이름·라벨로
// 나오는지 실제 HTTP로 확인한다(ISE-277). SLO 버킷 값은 config-repo/reservation-service.yml과 같게 둔다
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.config.import=", "spring.cloud.config.enabled=false", "eureka.client.enabled=false",
                "management.metrics.distribution.slo.http.server.requests=25ms,50ms,100ms,200ms,300ms,500ms,1s,3s"})
@ActiveProfiles("test")
class SeatHoldMetricsApiTest {
    private static final long USER = 61L;
    private static final long OTHER_USER = 62L;
    private static final String HOLD_URI = "/api/v1/schedules/{scheduleId}/reservations";
    private static final String SEATS_URI = "/api/v1/schedules/{scheduleId}/seats";

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final HttpClient http = HttpClient.newHttpClient();
    private final List<Long> createdVenueIds = new ArrayList<>();

    @LocalServerPort
    int port;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    MeterRegistry meters;

    private Long scheduleId;
    private List<Long> seatIds;

    @BeforeEach
    void setUp() {
        scheduleId = createSchedule();
        seatIds = createSeats(scheduleId, 2);
    }

    @AfterEach
    void cleanUp() {
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
        jdbc.update("DELETE FROM concerts WHERE title = 'ISE-277 지표 테스트' AND id NOT IN (SELECT concert_id FROM schedules)");
    }

    @Test
    void 선점_응답을_결과별로_센다() throws Exception {
        var held = count("held");
        var conflict = count("seat_conflict");
        var invalid = count("invalid");

        assertEquals(201, hold(USER, List.of(seatIds.get(0))));
        assertEquals(409, hold(OTHER_USER, List.of(seatIds.get(0))));
        assertEquals(400, hold(OTHER_USER, List.of(seatIds.get(1), 999_999_999L)));

        assertEquals(held + 1, count("held"));
        assertEquals(conflict + 1, count("seat_conflict"));
        assertEquals(invalid + 1, count("invalid"));
    }

    @Test
    void 결과_카운터는_요청이_없어도_모든_결과가_0부터_보인다() {
        for (var outcome : List.of("held", "accepted", "seat_conflict", "limit_exceeded", "not_open", "not_payable",
                "entry_token_used", "invalid", "error")) {
            assertTrue(meters.find("seathold.result").tag("outcome", outcome).counter() != null, outcome);
        }
    }

    @Test
    void 선점과_대시보드의_조회_API의_HTTP_지연이_SLO_버킷으로_기록된다() throws Exception {
        assertEquals(201, hold(USER, List.of(seatIds.get(0))));
        assertEquals(200, get("/api/v1/schedules/" + scheduleId + "/seats"));
        assertEquals(200, get("/api/v1/concerts"));
        get("/api/v1/concerts/" + Long.MAX_VALUE);
        get("/api/v1/reservations/requests/" + UUID.randomUUID());

        // Note: 응답을 쓴 뒤에 기록되므로 클라이언트가 응답을 받은 직후에는 아직 없을 수 있다
        assertBuckets("POST", HOLD_URI);
        assertBuckets("GET", SEATS_URI);
        assertBuckets("GET", "/api/v1/concerts");
        // 없는 ID의 404도 경로 템플릿으로 남는다. 실제 ID가 라벨에 들어가면 시계열이 요청마다 는다
        assertBuckets("GET", "/api/v1/concerts/{concertId}");
        assertBuckets("GET", "/api/v1/reservations/requests/{requestId}");
    }

    private int get(String path) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(base() + path))
                .header("X-User-Id", String.valueOf(USER))
                .header("X-User-Role", "USER")
                .GET().build();
        return http.send(request, HttpResponse.BodyHandlers.ofString()).statusCode();
    }

    private void assertBuckets(String method, String uri) throws InterruptedException {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (meters.find("http.server.requests").tag("method", method).tag("uri", uri).timer() == null
                && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        var timer = meters.find("http.server.requests").tag("method", method).tag("uri", uri).timer();
        assertTrue(timer != null, method + " " + uri);
        var bounds = Arrays.stream(timer.takeSnapshot().histogramCounts())
                .map(CountAtBucket::bucket)
                .map(nanos -> nanos / 1_000_000)
                .toList();
        assertEquals(List.of(25.0, 50.0, 100.0, 200.0, 300.0, 500.0, 1000.0, 3000.0), bounds);
    }

    private double count(String outcome) {
        return meters.get("seathold.result").tag("outcome", outcome).counter().count();
    }

    private String base() {
        return "http://127.0.0.1:" + port;
    }

    private Long createSchedule() {
        var venueId = jdbc.queryForObject("INSERT INTO venues (name, created_at, updated_at)"
                + " VALUES ('ISE-277 테스트 공연장', now(), now()) RETURNING id", Long.class);
        createdVenueIds.add(venueId);
        var concertId = jdbc.queryForObject("INSERT INTO concerts (title, version, created_at, updated_at)"
                + " VALUES ('ISE-277 지표 테스트', 0, now(), now()) RETURNING id", Long.class);
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
                    + " status, created_at, updated_at) VALUES (?, ?, 'A', 'VIP', 165000, 'AVAILABLE', now(), now())"
                    + " RETURNING id", Long.class, scheduleId, venueSeatId));
        }
        return ids;
    }

    private int hold(long userId, List<Long> seats) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(base() + "/api/v1/schedules/" + scheduleId + "/reservations"))
                .header("Content-Type", "application/json")
                .header("X-User-Id", String.valueOf(userId))
                .header("X-User-Role", "USER")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(Map.of("seatIds", seats))))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString()).statusCode();
    }
}
