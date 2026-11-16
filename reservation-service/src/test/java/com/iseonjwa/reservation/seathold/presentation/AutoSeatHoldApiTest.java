package com.iseonjwa.reservation.seathold.presentation;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

// 자동 배정 선점 API를 실제 HTTP와 PostgreSQL로 확인한다(ISE-264, contracts.md 1-3절)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.config.import=", "spring.cloud.config.enabled=false", "eureka.client.enabled=false"})
@ActiveProfiles("test")
class AutoSeatHoldApiTest {
    private static final long USER = 2640L;
    private static final long SEAT_PRICE = 165000L;
    private static final String TITLE = "ISE-264 자동 배정 테스트";

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final HttpClient http = HttpClient.newHttpClient();
    private final List<Long> createdVenueIds = new ArrayList<>();

    @LocalServerPort
    int port;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    PlatformTransactionManager transactionManager;

    private Long scheduleId;
    private Long lotteryA;
    private Long a1;
    private Long a2;
    private Long a3;
    private Long b1;
    private Long b2;

    @BeforeEach
    void setUp() {
        scheduleId = createSchedule();
        // 추첨석을 가장 작은 ID로 둔다. 자동 배정이 등급 조건을 빼먹으면 이 좌석을 먼저 잡는다
        lotteryA = createSeat("A", "LOTTERY");
        a1 = createSeat("A", "VIP");
        a2 = createSeat("A", "VIP");
        a3 = createSeat("A", "VIP");
        b1 = createSeat("B", "R");
        b2 = createSeat("B", "R");
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
        jdbc.update("DELETE FROM concerts WHERE title = ? AND id NOT IN (SELECT concert_id FROM schedules)", TITLE);
    }

    @Test
    void 구역과_매수로_선점하면_기존_선점과_같은_201_본문에_서버가_고른_좌석을_담는다() throws Exception {
        var key = UUID.randomUUID();

        var response = holdAuto(USER, key.toString(), body(null, "A", 2), "entry-jti-264");

        assertEquals(201, response.status(), response.json().toString());
        var data = response.json().get("data");
        assertEquals(10, data.size(), data.toString());
        assertEquals(key.toString(), data.get("requestId").asString());
        assertEquals(List.of(a1, a2), longs(data.get("seatIds")));
        assertEquals("HELD", data.get("status").asString());
        assertEquals(SEAT_PRICE * 2, data.get("totalAmount").asLong());
        assertFalse(data.get("paymentToken").isNull());
        assertEquals(List.of("AVAILABLE", "HELD", "HELD", "AVAILABLE", "AVAILABLE", "AVAILABLE"), seatStatuses());
        assertEquals(2, heldCount(USER));
        // 멱등키 행은 빈 배열로 먼저 쓰였다가 같은 트랜잭션에서 고른 좌석으로 채워진다
        var row = jdbc.queryForMap("SELECT seat_ids::text AS seats, status, auto_criteria, entry_token_jti"
                + " FROM seat_hold_requests WHERE request_id = ?", key);
        assertEquals("{" + a1 + "," + a2 + "}", row.get("seats"));
        assertEquals("HELD", row.get("status"));
        assertEquals("section=A;quantity=2", row.get("auto_criteria"));
        assertEquals("entry-jti-264", row.get("entry_token_jti"));
    }

    @Test
    void 등급이나_회차_전체로도_고르고_추첨석은_고르지_않는다() throws Exception {
        var byGrade = holdAuto(USER, UUID.randomUUID().toString(), body("R", null, 2), null);
        var anywhere = holdAuto(USER + 1, UUID.randomUUID().toString(), body(null, null, 1), null);

        assertEquals(List.of(b1, b2), longs(byGrade.json().get("data").get("seatIds")));
        assertEquals(List.of(a1), longs(anywhere.json().get("data").get("seatIds")));
        assertEquals("AVAILABLE", seatStatus(lotteryA));
    }

    @Test
    void 같은_멱등키_재요청은_처음_고른_좌석과_예매를_그대로_돌려준다() throws Exception {
        var key = UUID.randomUUID().toString();
        var first = holdAuto(USER, key, body(null, "A", 2), null);

        var second = holdAuto(USER, key, body(null, "A", 2), null);

        assertEquals(201, second.status(), second.json().toString());
        assertEquals(first.json().get("data").get("reservationId").asLong(),
                second.json().get("data").get("reservationId").asLong());
        assertEquals(List.of(a1, a2), longs(second.json().get("data").get("seatIds")));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM reservations WHERE request_id = ?::uuid",
                Integer.class, key));
        assertEquals(2, heldCount(USER));
        assertEquals("AVAILABLE", seatStatus(a3));
    }

    @Test
    void 같은_멱등키에_조건이_다르거나_좌석을_직접_보내면_409_IDEMPOTENCY_KEY_REUSED다() throws Exception {
        var key = UUID.randomUUID().toString();
        holdAuto(USER, key, body(null, "A", 2), null);

        assertFailure(holdAuto(USER, key, body(null, "A", 1), null), 409, "IDEMPOTENCY_KEY_REUSED");
        assertFailure(holdAuto(USER, key, body(null, "B", 2), null), 409, "IDEMPOTENCY_KEY_REUSED");
        assertFailure(holdAuto(USER, key, body("VIP", null, 2), null), 409, "IDEMPOTENCY_KEY_REUSED");
        assertFailure(holdAuto(USER + 1, key, body(null, "A", 2), null), 409, "IDEMPOTENCY_KEY_REUSED");
        // 좌석 직접 선택 API로 같은 키와 같은 좌석을 보내도 다른 요청이다
        var manual = send(request("/api/v1/schedules/" + scheduleId + "/reservations",
                mapper.writeValueAsString(Map.of("seatIds", List.of(a1, a2))), USER, key));
        assertFailure(manual, 409, "IDEMPOTENCY_KEY_REUSED");
        assertEquals(2, heldCount(USER));
    }

    @Test
    void 구역에_남은_좌석이_모자라면_409_ZONE_SOLD_OUT이고_다른_구역으로_넘기지_않는다() throws Exception {
        var key = UUID.randomUUID();

        assertFailure(holdAuto(USER, key.toString(), body(null, "B", 3), null), 409, "ZONE_SOLD_OUT");

        assertEquals(List.of("AVAILABLE", "AVAILABLE", "AVAILABLE", "AVAILABLE", "AVAILABLE", "AVAILABLE"),
                seatStatuses());
        assertEquals(0, heldCount(USER));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM seat_hold_requests WHERE request_id = ?",
                Integer.class, key));
    }

    @Test
    void 매수가_회차_한도를_넘으면_400이고_누적_매수가_넘으면_409_PURCHASE_LIMIT_EXCEEDED다() throws Exception {
        assertFailure(holdAuto(USER, UUID.randomUUID().toString(), body(null, null, 5), null), 400,
                "SEAT_COUNT_MISMATCH");
        assertEquals(201, holdAuto(USER, UUID.randomUUID().toString(), body(null, "A", 3), null).status());

        assertFailure(holdAuto(USER, UUID.randomUUID().toString(), body("R", null, 2), null), 409,
                "PURCHASE_LIMIT_EXCEEDED");
        assertEquals("AVAILABLE", seatStatus(b1));
        assertEquals(3, heldCount(USER));
    }

    @Test
    void 형식이_틀린_본문은_400_VALIDATION_FAILED다() throws Exception {
        assertFailure(holdAuto(USER, UUID.randomUUID().toString(), body("VIP", "A", 1), null), 400,
                "VALIDATION_FAILED");
        assertFailure(holdAuto(USER, UUID.randomUUID().toString(), body("LOTTERY", null, 1), null), 400,
                "VALIDATION_FAILED");
        assertFailure(holdAuto(USER, UUID.randomUUID().toString(), body("GOLD", null, 1), null), 400,
                "VALIDATION_FAILED");
        assertFailure(holdAuto(USER, UUID.randomUUID().toString(), body(null, "A", 0), null), 400,
                "VALIDATION_FAILED");
        assertEquals(0, heldCount(USER));
    }

    // SKIP LOCKED: 다른 트랜잭션이 앞 좌석 행을 쥐고 있어도 기다리지 않고 다음 빈 좌석을 잡는다.
    // 그냥 FOR UPDATE면 lock_timeout(500ms) 뒤 409가 된다
    @Test
    void 다른_트랜잭션이_잠근_좌석은_건너뛰고_다음_빈_좌석을_잡는다() {
        var transaction = new TransactionTemplate(transactionManager);
        var response = transaction.execute(status -> {
            jdbc.queryForObject("SELECT id FROM schedule_seats WHERE id = ? FOR UPDATE", Long.class, a1);
            try {
                return holdAuto(USER, UUID.randomUUID().toString(), body(null, "A", 1), null);
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
        });

        assertEquals(201, response.status(), response.json().toString());
        assertEquals(List.of(a2), longs(response.json().get("data").get("seatIds")));
        assertEquals("AVAILABLE", seatStatus(a1));
    }

    private Long createSchedule() {
        var venueId = jdbc.queryForObject("INSERT INTO venues (name, created_at, updated_at)"
                + " VALUES ('ISE-264 테스트 공연장', now(), now()) RETURNING id", Long.class);
        createdVenueIds.add(venueId);
        var concertId = jdbc.queryForObject("INSERT INTO concerts (title, version, created_at, updated_at)"
                + " VALUES (?, 0, now(), now()) RETURNING id", Long.class, TITLE);
        return jdbc.queryForObject("INSERT INTO schedules (concert_id, venue_id, starts_at, open_at, version,"
                + " created_at, updated_at) VALUES (?, ?, now() + interval '1 day', now() - interval '1 minute',"
                + " 0, now(), now()) RETURNING id", Long.class, concertId, venueId);
    }

    // 좌석 ID는 만든 순서대로 커진다. 공연장 좌석 등급은 LOTTERY가 될 수 없어 회차 좌석에만 LOTTERY를 둔다(D9)
    private Long createSeat(String section, String grade) {
        var venueId = jdbc.queryForObject("SELECT venue_id FROM schedules WHERE id = ?", Long.class, scheduleId);
        var seatNo = jdbc.queryForObject("SELECT count(*) + 1 FROM venue_seats WHERE venue_id = ?", Integer.class,
                venueId);
        var venueGrade = grade.equals("LOTTERY") ? "VIP" : grade;
        var venueSeatId = jdbc.queryForObject("INSERT INTO venue_seats (venue_id, section, row_no, seat_no, grade,"
                + " created_at, updated_at) VALUES (?, ?, 1, ?, ?, now(), now()) RETURNING id",
                Long.class, venueId, section, seatNo, venueGrade);
        return jdbc.queryForObject("INSERT INTO schedule_seats (schedule_id, venue_seat_id, section, grade, price,"
                + " status, created_at, updated_at) VALUES (?, ?, ?, ?, ?, 'AVAILABLE', now(), now()) RETURNING id",
                Long.class, scheduleId, venueSeatId, section, grade, SEAT_PRICE);
    }

    private List<String> seatStatuses() {
        var statuses = new ArrayList<String>();
        for (var seatId : List.of(lotteryA, a1, a2, a3, b1, b2)) {
            statuses.add(seatStatus(seatId));
        }
        return statuses;
    }

    private String seatStatus(Long seatId) {
        return jdbc.queryForObject("SELECT status FROM schedule_seats WHERE id = ?", String.class, seatId);
    }

    private int heldCount(long userId) {
        return jdbc.queryForObject("SELECT COALESCE(SUM(held_count), 0) FROM user_schedule_quotas"
                + " WHERE user_id = ? AND schedule_id = ?", Integer.class, userId, scheduleId);
    }

    private Response holdAuto(long userId, String key, String body, String entryTokenJti) throws Exception {
        var request = request("/api/v1/schedules/" + scheduleId + "/reservations/auto", body, userId, key);
        if (entryTokenJti != null) {
            request.header("X-Entry-Token-Id", entryTokenJti);
        }
        return send(request);
    }

    private HttpRequest.Builder request(String path, String body, long userId, String key) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Content-Type", "application/json")
                .header("X-User-Id", String.valueOf(userId))
                .header("X-User-Role", "USER")
                .header("Idempotency-Key", key)
                .POST(HttpRequest.BodyPublishers.ofString(body));
    }

    private Response send(HttpRequest.Builder request) throws Exception {
        var response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        return new Response(response.statusCode(), mapper.readTree(response.body()));
    }

    private String body(String grade, String section, int quantity) {
        var body = new LinkedHashMap<String, Object>();
        if (grade != null) {
            body.put("grade", grade);
        }
        if (section != null) {
            body.put("section", section);
        }
        body.put("quantity", quantity);
        return mapper.writeValueAsString(body);
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

    private record Response(int status, JsonNode json) {}
}
