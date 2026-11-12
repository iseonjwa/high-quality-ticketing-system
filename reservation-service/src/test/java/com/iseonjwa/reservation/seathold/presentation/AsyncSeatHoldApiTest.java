package com.iseonjwa.reservation.seathold.presentation;

import com.iseonjwa.reservation.seathold.application.HoldSeatsCommand;
import com.iseonjwa.reservation.seathold.application.SyncHoldOutcome;
import com.iseonjwa.reservation.seathold.application.SyncSeatHoldService;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// 접수형 좌석 선점 접수와 결과 조회 API를 실제 HTTP와 PostgreSQL로 확인한다(ISE-16, contracts.md 2-2·3-2, ADR 0053·0054)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.config.import=", "spring.cloud.config.enabled=false", "eureka.client.enabled=false",
                "seat-hold.mode=async"})
@ActiveProfiles("test")
class AsyncSeatHoldApiTest {
    private static final long USER = 43L;
    private static final long OTHER_USER = 44L;
    private static final long SEAT_PRICE = 165000L;

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final HttpClient http = HttpClient.newHttpClient();
    private final List<Long> createdVenueIds = new ArrayList<>();

    @LocalServerPort
    int port;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    SyncSeatHoldService syncService;

    private Long scheduleId;
    private List<Long> seatIds;

    @BeforeEach
    void setUp() {
        scheduleId = createSchedule("now() - interval '1 minute'", "now() + interval '1 day'");
        seatIds = createSeats(scheduleId, "A", "VIP", 3);
    }

    @AfterEach
    void cleanUp() {
        for (var venueId : createdVenueIds) {
            var scheduleIds = "(SELECT id FROM schedules WHERE venue_id = ?)";
            var reservationIds = "(SELECT id FROM reservations WHERE schedule_id IN " + scheduleIds + ")";
            jdbc.update("DELETE FROM outbox_events WHERE event_type = 'SeatHoldRequested'"
                    + " AND (payload -> 'payload' ->> 'scheduleId')::bigint IN " + scheduleIds, venueId);
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
        jdbc.update("DELETE FROM concerts WHERE title = 'ISE-16 접수형 테스트' AND id NOT IN (SELECT concert_id FROM schedules)");
    }

    @Test
    void 접수하면_좌석을_잠그지_않고_202와_Location을_돌려준다() throws Exception {
        var key = UUID.randomUUID();
        var requested = List.of(seatIds.get(1), seatIds.get(0));

        var response = hold(USER, key.toString(), scheduleId, requested);

        assertEquals(202, response.status(), response.json().toString());
        assertEquals("/api/v1/reservations/requests/" + key, response.location());
        var data = response.json().get("data");
        assertEquals(3, data.size(), data.toString());
        assertEquals(key.toString(), data.get("requestId").asString());
        assertEquals("REQUESTED", data.get("status").asString());
        assertEquals(1, data.get("nextPollAfterSeconds").asInt());

        assertEquals(List.of("AVAILABLE", "AVAILABLE", "AVAILABLE"), seatStatuses(seatIds));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM reservations WHERE request_id = ?", Integer.class, key));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM user_schedule_quotas WHERE user_id = ?"
                + " AND schedule_id = ?", Integer.class, USER, scheduleId));
        assertEquals("REQUESTED", jdbc.queryForObject("SELECT status FROM seat_hold_requests WHERE request_id = ?",
                String.class, key));
        assertEquals("{" + seatIds.get(0) + "," + seatIds.get(1) + "}", jdbc.queryForObject(
                "SELECT seat_ids::text FROM seat_hold_requests WHERE request_id = ?", String.class, key));
    }

    @Test
    void 접수와_같은_트랜잭션에_SeatHoldRequested_Outbox_행을_남긴다() throws Exception {
        var key = UUID.randomUUID();

        hold(USER, key.toString(), scheduleId, List.of(seatIds.get(1), seatIds.get(0)));

        var rows = outboxRows(key);
        assertEquals(1, rows.size(), rows.toString());
        var row = rows.getFirst();
        assertEquals("seat-hold.request", row.get("topic"));
        assertEquals(scheduleId + ":A", row.get("message_key"));
        assertEquals("PENDING", row.get("status"));
        var payload = mapper.readTree((String) row.get("payload"));
        assertEquals(key.toString(), payload.get("requestId").asString());
        assertEquals(USER, payload.get("userId").asLong());
        assertEquals(scheduleId, payload.get("scheduleId").asLong());
        assertEquals("A", payload.get("section").asString());
        assertEquals(List.of(seatIds.get(0), seatIds.get(1)), longs(payload.get("seatIds")));
        assertNotNull(payload.get("requestedAt"));
    }

    @Test
    void 좌석이_여러_구역에_걸치면_가장_작은_구역을_키로_쓴다() throws Exception {
        var sectionC = createSeats(scheduleId, "C", "R", 1).getFirst();
        var sectionB = createSeats(scheduleId, "B", "VIP", 1).getFirst();
        var key = UUID.randomUUID();

        var response = hold(USER, key.toString(), scheduleId, List.of(sectionC, sectionB));

        assertEquals(202, response.status(), response.json().toString());
        var row = outboxRows(key).getFirst();
        assertEquals(scheduleId + ":B", row.get("message_key"));
        assertEquals("B", mapper.readTree((String) row.get("payload")).get("section").asString());
    }

    @Test
    void 이미_잡힌_좌석이_보이면_행을_남기지_않고_즉시_409를_준다() throws Exception {
        jdbc.update("UPDATE schedule_seats SET status = 'HELD' WHERE id = ?", seatIds.get(1));
        var key = UUID.randomUUID();

        var response = hold(USER, key.toString(), scheduleId, List.of(seatIds.get(0), seatIds.get(1)));

        assertFailure(response, 409, "SEAT_ALREADY_HELD");
        assertNoTrace(key);
    }

    @Test
    void 추첨석이_섞이면_즉시_409를_준다() throws Exception {
        var lotterySeat = createSeats(scheduleId, "A", "LOTTERY", 1).getFirst();
        var key = UUID.randomUUID();

        var response = hold(USER, key.toString(), scheduleId, List.of(seatIds.get(0), lotterySeat));

        assertFailure(response, 409, "SEAT_ALREADY_HELD");
        assertNoTrace(key);
    }

    @Test
    void 즉시_거절된_키로_다시_보내면_새로_판단한다() throws Exception {
        jdbc.update("UPDATE schedule_seats SET status = 'HELD' WHERE id = ?", seatIds.get(0));
        var key = UUID.randomUUID().toString();
        assertFailure(hold(USER, key, scheduleId, List.of(seatIds.get(0))), 409, "SEAT_ALREADY_HELD");

        jdbc.update("UPDATE schedule_seats SET status = 'AVAILABLE' WHERE id = ?", seatIds.get(0));
        var retry = hold(USER, key, scheduleId, List.of(seatIds.get(0)));

        assertEquals(202, retry.status(), retry.json().toString());
    }

    @Test
    void 동기형과_같은_입력_검증을_접수에서_한다() throws Exception {
        var otherSchedule = createSchedule("now() - interval '1 minute'", "now() + interval '1 day'");
        var otherSeat = createSeats(otherSchedule, "A", "VIP", 1).getFirst();
        var notOpen = createSchedule("now() + interval '1 hour'", "now() + interval '1 day'");
        var notOpenSeat = createSeats(notOpen, "A", "VIP", 1).getFirst();

        assertFailure(hold(USER, UUID.randomUUID().toString(), scheduleId, List.of(seatIds.get(0), seatIds.get(0))),
                400, "SEAT_COUNT_MISMATCH");
        assertFailure(hold(USER, UUID.randomUUID().toString(), scheduleId, List.of(seatIds.get(0), otherSeat)),
                400, "SEAT_COUNT_MISMATCH");
        assertFailure(hold(USER, UUID.randomUUID().toString(), notOpen, List.of(notOpenSeat)), 409, "SALE_NOT_OPEN");
        assertFailure(hold(USER, UUID.randomUUID().toString(), Long.MAX_VALUE, List.of(seatIds.get(0))),
                404, "NOT_FOUND");
        assertFailure(hold(USER, "not-a-uuid", scheduleId, List.of(seatIds.get(0))), 400, "VALIDATION_FAILED");
    }

    @Test
    void 같은_키와_같은_본문으로_다시_보내면_같은_202를_주고_행을_더_만들지_않는다() throws Exception {
        var key = UUID.randomUUID();
        hold(USER, key.toString(), scheduleId, List.of(seatIds.get(0), seatIds.get(1)));

        // 순서만 다른 같은 좌석은 같은 요청이다(contracts.md 3-1)
        var retry = hold(USER, key.toString(), scheduleId, List.of(seatIds.get(1), seatIds.get(0)));

        assertEquals(202, retry.status(), retry.json().toString());
        assertEquals("/api/v1/reservations/requests/" + key, retry.location());
        assertEquals("REQUESTED", retry.json().get("data").get("status").asString());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM seat_hold_requests WHERE request_id = ?",
                Integer.class, key));
        assertEquals(1, outboxRows(key).size());
    }

    @Test
    void 이미_결과가_난_요청도_같은_키_재요청에는_REQUESTED_202를_준다() throws Exception {
        var key = UUID.randomUUID();
        hold(USER, key.toString(), scheduleId, List.of(seatIds.get(0)));
        jdbc.update("UPDATE seat_hold_requests SET status = 'REJECTED', reason_code = 'SEAT_ALREADY_HELD'"
                + " WHERE request_id = ?", key);

        var retry = hold(USER, key.toString(), scheduleId, List.of(seatIds.get(0)));

        // 202 본문에 결과를 섞으면 HELD를 즉시 완료로 분기하는 클라이언트가 reservation 블록 없이 넘어간다
        assertEquals(202, retry.status(), retry.json().toString());
        assertEquals("REQUESTED", retry.json().get("data").get("status").asString());
    }

    @Test
    void 같은_키를_다른_본문이나_다른_사용자가_쓰면_409를_준다() throws Exception {
        var key = UUID.randomUUID().toString();
        hold(USER, key, scheduleId, List.of(seatIds.get(0)));

        assertFailure(hold(USER, key, scheduleId, List.of(seatIds.get(1))), 409, "IDEMPOTENCY_KEY_REUSED");
        assertFailure(hold(OTHER_USER, key, scheduleId, List.of(seatIds.get(0))), 409, "IDEMPOTENCY_KEY_REUSED");
    }

    @Test
    void 같은_키가_동시에_와도_둘_다_같은_202를_받고_요청은_하나만_남는다() throws Exception {
        var key = UUID.randomUUID().toString();
        Callable<Response> call = () -> hold(USER, key, scheduleId, List.of(seatIds.get(0)));

        List<Response> responses;
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(call);
            var second = executor.submit(call);
            responses = List.of(first.get(), second.get());
        }

        for (var response : responses) {
            assertEquals(202, response.status(), response.json().toString());
        }
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM seat_hold_requests WHERE request_id = ?::uuid",
                Integer.class, key));
        assertEquals(1, outboxRows(UUID.fromString(key)).size());
    }

    @Test
    void 처리_중인_요청을_조회하면_다음_조회_간격을_준다() throws Exception {
        var key = UUID.randomUUID().toString();
        hold(USER, key, scheduleId, List.of(seatIds.get(0)));

        var response = find(USER, key);

        assertEquals(200, response.status(), response.json().toString());
        var data = response.json().get("data");
        assertEquals(3, data.size(), data.toString());
        assertEquals(key, data.get("requestId").asString());
        assertEquals("REQUESTED", data.get("status").asString());
        assertEquals(1, data.get("nextPollAfterSeconds").asInt());
    }

    @Test
    void 거절된_요청을_조회하면_사유를_준다() throws Exception {
        var key = UUID.randomUUID().toString();
        hold(USER, key, scheduleId, List.of(seatIds.get(0)));
        jdbc.update("UPDATE seat_hold_requests SET status = 'REJECTED', reason_code = 'PURCHASE_LIMIT_EXCEEDED'"
                + " WHERE request_id = ?::uuid", key);

        var data = find(USER, key).json().get("data");

        assertEquals(3, data.size(), data.toString());
        assertEquals("REJECTED", data.get("status").asString());
        assertEquals("PURCHASE_LIMIT_EXCEEDED", data.get("reasonCode").asString());
    }

    @Test
    void 선점된_요청을_조회하면_동기형과_같은_예매_블록과_결제_토큰을_준다() throws Exception {
        // 모드와 상관없이 같은 행을 읽으므로 동기형으로 선점한 결과도 조회된다(ADR 0054)
        var key = UUID.randomUUID();
        syncService.hold(new HoldSeatsCommand(USER, key, scheduleId, List.of(seatIds.get(1), seatIds.get(0))));

        var response = find(USER, key.toString());

        assertEquals(200, response.status(), response.json().toString());
        var data = response.json().get("data");
        assertEquals(3, data.size(), data.toString());
        assertEquals("HELD", data.get("status").asString());
        var reservation = data.get("reservation");
        assertEquals(9, reservation.size(), reservation.toString());
        assertEquals(scheduleId, reservation.get("scheduleId").asLong());
        assertEquals(List.of(seatIds.get(0), seatIds.get(1)), longs(reservation.get("seatIds")));
        assertEquals("HELD", reservation.get("status").asString());
        assertEquals(SEAT_PRICE * 2, reservation.get("totalAmount").asLong());
        assertTrue(reservation.get("holdExpiresAt").asString().endsWith("+09:00"));
        assertFalse(reservation.get("paymentToken").isNull());
        assertFalse(reservation.get("paymentTokenExpiresAt").isNull());
    }

    @Test
    void 결제가_끝난_예매는_현재_상태를_주고_결제_토큰은_null이다() throws Exception {
        var key = UUID.randomUUID();
        var held = (SyncHoldOutcome.Held) syncService.hold(new HoldSeatsCommand(USER, key, scheduleId, List.of(seatIds.get(0))));
        jdbc.update("UPDATE reservations SET status = 'CONFIRMED' WHERE id = ?", held.result().reservationId());

        var reservation = find(USER, key.toString()).json().get("data").get("reservation");

        assertEquals("CONFIRMED", reservation.get("status").asString());
        assertTrue(reservation.get("paymentToken").isNull(), reservation.toString());
        assertTrue(reservation.get("paymentTokenExpiresAt").isNull(), reservation.toString());
    }

    @Test
    void 남의_요청이나_없는_요청은_똑같이_404다() throws Exception {
        var key = UUID.randomUUID().toString();
        hold(USER, key, scheduleId, List.of(seatIds.get(0)));

        assertFailure(find(OTHER_USER, key), 404, "NOT_FOUND");
        assertFailure(find(USER, UUID.randomUUID().toString()), 404, "NOT_FOUND");
        assertFailure(find(USER, "not-a-uuid"), 400, "VALIDATION_FAILED");
    }

    private Long createSchedule(String openAt, String startsAt) {
        var venueId = jdbc.queryForObject("INSERT INTO venues (name, created_at, updated_at)"
                + " VALUES ('ISE-16 테스트 공연장', now(), now()) RETURNING id", Long.class);
        createdVenueIds.add(venueId);
        var concertId = jdbc.queryForObject("INSERT INTO concerts (title, version, created_at, updated_at)"
                + " VALUES ('ISE-16 접수형 테스트', 0, now(), now()) RETURNING id", Long.class);
        return jdbc.queryForObject("INSERT INTO schedules (concert_id, venue_id, starts_at, open_at, version,"
                + " created_at, updated_at) VALUES (?, ?, " + startsAt + ", " + openAt + ", 0, now(), now())"
                + " RETURNING id", Long.class, concertId, venueId);
    }

    private List<Long> createSeats(Long scheduleId, String section, String grade, int count) {
        var venueId = jdbc.queryForObject("SELECT venue_id FROM schedules WHERE id = ?", Long.class, scheduleId);
        var nextSeatNo = jdbc.queryForObject("SELECT count(*) + 1 FROM venue_seats WHERE venue_id = ?",
                Integer.class, venueId);
        var ids = new ArrayList<Long>();
        for (var i = 0; i < count; i++) {
            var venueSeatId = jdbc.queryForObject("INSERT INTO venue_seats (venue_id, section, row_no, seat_no, grade,"
                    + " created_at, updated_at) VALUES (?, ?, 1, ?, ?, now(), now()) RETURNING id",
                    Long.class, venueId, section, nextSeatNo + i, grade);
            ids.add(jdbc.queryForObject("INSERT INTO schedule_seats (schedule_id, venue_seat_id, section, grade, price,"
                    + " status, created_at, updated_at) VALUES (?, ?, ?, ?, ?, 'AVAILABLE', now(), now())"
                    + " RETURNING id", Long.class, scheduleId, venueSeatId, section, grade, SEAT_PRICE));
        }
        return ids;
    }

    private List<String> seatStatuses(List<Long> ids) {
        var statuses = new ArrayList<String>();
        for (var seatId : ids) {
            statuses.add(jdbc.queryForObject("SELECT status FROM schedule_seats WHERE id = ?", String.class, seatId));
        }
        return statuses;
    }

    private List<Map<String, Object>> outboxRows(UUID key) {
        return jdbc.queryForList("SELECT topic, message_key, status, (payload -> 'payload')::text AS payload"
                + " FROM outbox_events WHERE event_type = 'SeatHoldRequested'"
                + " AND payload -> 'payload' ->> 'requestId' = ?", key.toString());
    }

    // 즉시 거절은 seat_hold_requests·Outbox 행을 남기지 않는다(ADR 0054)
    private void assertNoTrace(UUID key) {
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM seat_hold_requests WHERE request_id = ?",
                Integer.class, key));
        assertEquals(0, outboxRows(key).size());
    }

    private Response hold(long userId, String key, Long scheduleId, List<Long> seatIds) throws Exception {
        return send(HttpRequest.newBuilder(uri("/api/v1/schedules/" + scheduleId + "/reservations"))
                .header("Content-Type", "application/json")
                .header("X-User-Id", String.valueOf(userId))
                .header("X-User-Role", "USER")
                .header("Idempotency-Key", key)
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(Map.of("seatIds", seatIds)))));
    }

    private Response find(long userId, String requestId) throws Exception {
        return send(HttpRequest.newBuilder(uri("/api/v1/reservations/requests/" + requestId))
                .header("X-User-Id", String.valueOf(userId))
                .header("X-User-Role", "USER")
                .GET());
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }

    private Response send(HttpRequest.Builder request) throws Exception {
        var response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        return new Response(response.statusCode(), mapper.readTree(response.body()),
                response.headers().firstValue("Location").orElse(null));
    }

    private static List<Long> longs(JsonNode array) {
        var values = new ArrayList<Long>();
        array.forEach(node -> values.add(node.asLong()));
        return values;
    }

    private static void assertFailure(Response response, int status, String code) {
        assertEquals(status, response.status(), response.json().toString());
        assertEquals(code, response.json().get("code").asString(), response.json().toString());
        assertFalse(response.json().has("data"), response.json().toString());
    }

    private record Response(int status, JsonNode json, String location) {}
}
