package com.iseonjwa.reservation.seathold.presentation;

import com.iseonjwa.reservation.seathold.application.SeatHoldDirectPublisher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

// 접수형 직접 발행(측정 전용, ISE-18)을 실제 HTTP와 PostgreSQL로 본다. Kafka 전송은 포트를 바꿔 끼워 호출만 확인한다
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.config.import=", "spring.cloud.config.enabled=false", "eureka.client.enabled=false",
                "seat-hold.mode=async", "seat-hold.async.publish=direct"})
@ActiveProfiles("test")
class AsyncSeatHoldDirectPublishApiTest {
    private static final long USER = 45L;
    private static final long SEAT_PRICE = 165000L;
    private static final String TITLE = "ISE-18 직접 발행 테스트";

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final HttpClient http = HttpClient.newHttpClient();
    private final List<Long> createdVenueIds = new ArrayList<>();

    @LocalServerPort
    int port;
    @Autowired
    JdbcTemplate jdbc;
    @MockitoBean
    SeatHoldDirectPublisher publisher;

    private Long scheduleId;
    private List<Long> seatIds;

    @BeforeEach
    void setUp() {
        scheduleId = createSchedule();
        seatIds = createSeats(scheduleId, "A", 2);
    }

    @AfterEach
    void cleanUp() {
        for (var venueId : createdVenueIds) {
            var scheduleIds = "(SELECT id FROM schedules WHERE venue_id = ?)";
            jdbc.update("DELETE FROM outbox_events WHERE event_type = 'SeatHoldRequested'"
                    + " AND (payload -> 'payload' ->> 'scheduleId')::bigint IN " + scheduleIds, venueId);
            jdbc.update("DELETE FROM seat_hold_requests WHERE schedule_id IN " + scheduleIds, venueId);
            jdbc.update("DELETE FROM schedule_seats WHERE schedule_id IN " + scheduleIds, venueId);
            jdbc.update("DELETE FROM schedules WHERE venue_id = ?", venueId);
            jdbc.update("DELETE FROM venue_seats WHERE venue_id = ?", venueId);
            jdbc.update("DELETE FROM venues WHERE id = ?", venueId);
        }
        jdbc.update("DELETE FROM concerts WHERE title = ? AND id NOT IN (SELECT concert_id FROM schedules)", TITLE);
    }

    @Test
    void 요청_행을_커밋한_뒤_Outbox_없이_같은_키와_페이로드로_보낸다() throws Exception {
        var key = UUID.randomUUID();
        // 보내는 순간 다른 커넥션에서 요청 행이 보여야 커밋 뒤 전송이다
        var statusAtPublish = new AtomicReference<String>();
        doAnswer(invocation -> {
            statusAtPublish.set(jdbc.queryForObject("SELECT status FROM seat_hold_requests WHERE request_id = ?",
                    String.class, key));
            return null;
        }).when(publisher).publish(anyString(), anyString(), anyString(), anyInt(), any());

        var response = hold(key, List.of(seatIds.get(1), seatIds.get(0)));

        assertEquals(202, response.status(), response.json().toString());
        assertEquals("REQUESTED", statusAtPublish.get());
        verify(publisher).publish(eq("seat-hold.request"), eq(scheduleId + ":A"), eq("SeatHoldRequested"), eq(1),
                any());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE event_type = 'SeatHoldRequested'"
                + " AND payload -> 'payload' ->> 'requestId' = ?", Integer.class, key.toString()));
    }

    @Test
    void 같은_키_재요청은_다시_보내지_않는다() throws Exception {
        var key = UUID.randomUUID();

        assertEquals(202, hold(key, List.of(seatIds.get(0))).status());
        assertEquals(202, hold(key, List.of(seatIds.get(0))).status());

        verify(publisher, times(1)).publish(anyString(), anyString(), anyString(), anyInt(), any());
    }

    @Test
    void 접수에서_거절된_요청은_보내지_않는다() throws Exception {
        jdbc.update("UPDATE schedule_seats SET status = 'HELD' WHERE id = ?", seatIds.get(0));

        var response = hold(UUID.randomUUID(), List.of(seatIds.get(0)));

        assertEquals(409, response.status(), response.json().toString());
        verify(publisher, never()).publish(anyString(), anyString(), anyString(), anyInt(), any());
    }

    private Long createSchedule() {
        var venueId = jdbc.queryForObject("INSERT INTO venues (name, created_at, updated_at)"
                + " VALUES ('ISE-18 테스트 공연장', now(), now()) RETURNING id", Long.class);
        createdVenueIds.add(venueId);
        var concertId = jdbc.queryForObject("INSERT INTO concerts (title, version, created_at, updated_at)"
                + " VALUES (?, 0, now(), now()) RETURNING id", Long.class, TITLE);
        return jdbc.queryForObject("INSERT INTO schedules (concert_id, venue_id, starts_at, open_at, version,"
                + " created_at, updated_at) VALUES (?, ?, now() + interval '1 day', now() - interval '1 minute', 0,"
                + " now(), now()) RETURNING id", Long.class, concertId, venueId);
    }

    private List<Long> createSeats(Long scheduleId, String section, int count) {
        var venueId = jdbc.queryForObject("SELECT venue_id FROM schedules WHERE id = ?", Long.class, scheduleId);
        var ids = new ArrayList<Long>();
        for (var i = 0; i < count; i++) {
            var venueSeatId = jdbc.queryForObject("INSERT INTO venue_seats (venue_id, section, row_no, seat_no, grade,"
                    + " created_at, updated_at) VALUES (?, ?, 1, ?, 'VIP', now(), now()) RETURNING id",
                    Long.class, venueId, section, i + 1);
            ids.add(jdbc.queryForObject("INSERT INTO schedule_seats (schedule_id, venue_seat_id, section, grade, price,"
                    + " status, created_at, updated_at) VALUES (?, ?, ?, 'VIP', ?, 'AVAILABLE', now(), now())"
                    + " RETURNING id", Long.class, scheduleId, venueSeatId, section, SEAT_PRICE));
        }
        return ids;
    }

    private Response hold(UUID key, List<Long> seats) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/schedules/" + scheduleId
                        + "/reservations"))
                .header("Content-Type", "application/json")
                .header("X-User-Id", String.valueOf(USER))
                .header("X-User-Role", "USER")
                .header("Idempotency-Key", key.toString())
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(Map.of("seatIds", seats))))
                .build();
        var response = http.send(request, HttpResponse.BodyHandlers.ofString());
        return new Response(response.statusCode(), mapper.readTree(response.body()));
    }

    private record Response(int status, JsonNode json) {}
}
