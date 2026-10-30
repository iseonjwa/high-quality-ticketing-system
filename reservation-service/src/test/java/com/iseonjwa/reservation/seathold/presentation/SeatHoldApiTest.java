package com.iseonjwa.reservation.seathold.presentation;

import com.iseonjwa.common.auth.TokenDecoders;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// 동기형 좌석 일괄 선점 API를 실제 HTTP와 PostgreSQL로 확인한다(ISE-11, contracts.md 1·2-1·4절)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.config.import=", "spring.cloud.config.enabled=false", "eureka.client.enabled=false"})
@ActiveProfiles("test")
class SeatHoldApiTest {
    private static final long USER = 42L;
    private static final long SEAT_PRICE = 165000L;

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final HttpClient http = HttpClient.newHttpClient();
    private final List<Long> createdVenueIds = new ArrayList<>();

    @LocalServerPort
    int port;
    @Autowired
    JdbcTemplate jdbc;
    @Value("${payment.token.secret}")
    String paymentTokenSecret;

    private Long scheduleId;
    private List<Long> seatIds;

    @BeforeEach
    void setUp() {
        scheduleId = createSchedule("now() - interval '1 minute'", "now() + interval '1 day'");
        seatIds = createSeats(scheduleId, 3);
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
        jdbc.update("DELETE FROM concerts WHERE title = 'ISE-11 선점 테스트' AND id NOT IN (SELECT concert_id FROM schedules)");
    }

    @Test
    void 선점에_성공하면_201과_계약의_10개_필드를_돌려준다() throws Exception {
        var key = UUID.randomUUID().toString();
        // 요청 순서와 상관없이 오름차순으로 처리·응답한다
        var requested = List.of(seatIds.get(1), seatIds.get(0));

        var response = hold(USER, key, scheduleId, requested);

        assertEquals(201, response.status(), response.json().toString());
        assertEquals("선점되었습니다", response.json().get("message").asString());
        var data = response.json().get("data");
        assertEquals(10, data.size(), data.toString());
        assertEquals(key, data.get("requestId").asString());
        assertTrue(data.get("reservationId").isIntegralNumber());
        assertTrue(data.get("reservationNo").asString().matches("R\\d{8}-[0-9A-Z]{8}"));
        assertEquals(scheduleId, data.get("scheduleId").asLong());
        assertEquals(List.of(seatIds.get(0), seatIds.get(1)), longs(data.get("seatIds")));
        assertEquals("HELD", data.get("status").asString());
        assertTrue(data.get("totalAmount").isIntegralNumber());
        assertEquals(SEAT_PRICE * 2, data.get("totalAmount").asLong());

        var holdExpiresAt = OffsetDateTime.parse(data.get("holdExpiresAt").asString());
        var tokenExpiresAt = OffsetDateTime.parse(data.get("paymentTokenExpiresAt").asString());
        assertTrue(data.get("holdExpiresAt").asString().endsWith("+09:00"), data.get("holdExpiresAt").asString());
        assertEquals(holdExpiresAt.minusSeconds(10), tokenExpiresAt);
        var untilExpiry = Duration.between(OffsetDateTime.now(), holdExpiresAt);
        assertTrue(untilExpiry.compareTo(Duration.ofMinutes(4)) > 0 && untilExpiry.compareTo(Duration.ofMinutes(5)) <= 0,
                untilExpiry.toString());

        var jwt = paymentTokenDecoder().decode(data.get("paymentToken").asString());
        assertEquals(String.valueOf(USER), jwt.getSubject());
        assertEquals(data.get("reservationId").asLong(), ((Number) jwt.getClaim("reservationId")).longValue());
        assertEquals(SEAT_PRICE * 2, ((Number) jwt.getClaim("amount")).longValue());
        assertEquals(tokenExpiresAt.toInstant(), jwt.getExpiresAt());
    }

    @Test
    void 선점에_성공하면_좌석_매수_예매_원장을_한_번에_남긴다() throws Exception {
        var key = UUID.randomUUID();
        var requested = List.of(seatIds.get(0), seatIds.get(1));

        var response = hold(USER, key.toString(), scheduleId, requested);

        assertEquals(201, response.status(), response.json().toString());
        var reservationId = response.json().get("data").get("reservationId").asLong();
        assertEquals(List.of("HELD", "HELD", "AVAILABLE"), seatStatuses());
        assertEquals(2, heldCount(USER));
        assertEquals("HELD", jdbc.queryForObject("SELECT status FROM reservations WHERE id = ?", String.class, reservationId));
        assertEquals(key, jdbc.queryForObject("SELECT request_id FROM reservations WHERE id = ?", UUID.class, reservationId));
        assertEquals(2, jdbc.queryForObject("SELECT quantity FROM reservations WHERE id = ?", Integer.class, reservationId));
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM reservation_seats WHERE reservation_id = ?"
                + " AND status = 'HELD' AND price = ?", Integer.class, reservationId, SEAT_PRICE));
        assertEquals("HELD", jdbc.queryForObject("SELECT status FROM seat_hold_requests WHERE request_id = ?"
                + " AND reservation_id = ?", String.class, key, reservationId));
        assertEquals("{" + requested.get(0) + "," + requested.get(1) + "}", jdbc.queryForObject(
                "SELECT seat_ids::text FROM seat_hold_requests WHERE request_id = ?", String.class, key));
        assertEquals("HELD", jdbc.queryForObject("SELECT to_status FROM reservation_status_histories"
                + " WHERE reservation_id = ? AND from_status IS NULL", String.class, reservationId));
    }

    @Test
    void 이미_선점된_좌석이_하나라도_섞이면_409_SEAT_ALREADY_HELD이고_전체를_되돌린다() throws Exception {
        assertEquals(201, hold(USER + 1, UUID.randomUUID().toString(), scheduleId, List.of(seatIds.get(1))).status());
        var key = UUID.randomUUID();

        var response = hold(USER, key.toString(), scheduleId, List.of(seatIds.get(0), seatIds.get(1)));

        assertFailure(response, 409, "SEAT_ALREADY_HELD");
        assertEquals(List.of("AVAILABLE", "HELD", "AVAILABLE"), seatStatuses());
        assertEquals(0, heldCount(USER));
        assertNoTrace(key);
    }

    @Test
    void 좌석_상태와_예매_좌석_원장이_어긋나도_부분_유니크_인덱스가_중복_선점을_막는다() throws Exception {
        // 좌석은 AVAILABLE로 보이지만 다른 예매가 이미 HELD로 잡고 있는 상태(대사 불일치)를 만든다
        var otherReservation = jdbc.queryForObject("INSERT INTO reservations (reservation_no, request_id, user_id,"
                + " schedule_id, status, quantity, total_amount, hold_expires_at, created_at, updated_at)"
                + " VALUES ('R-ISE11-CONFLICT', ?, 7, ?, 'HELD', 1, ?, now() + interval '5 minute', now(), now())"
                + " RETURNING id", Long.class, UUID.randomUUID(), scheduleId, SEAT_PRICE);
        jdbc.update("INSERT INTO reservation_seats (reservation_id, schedule_seat_id, price, status, created_at, updated_at)"
                + " VALUES (?, ?, ?, 'HELD', now(), now())", otherReservation, seatIds.get(0), SEAT_PRICE);
        var key = UUID.randomUUID();

        var response = hold(USER, key.toString(), scheduleId, List.of(seatIds.get(0)));

        assertFailure(response, 409, "SEAT_ALREADY_HELD");
        assertEquals(List.of("AVAILABLE", "AVAILABLE", "AVAILABLE"), seatStatuses());
        assertEquals(0, heldCount(USER));
        assertNoTrace(key);
    }

    @Test
    void 오픈_전_회차는_409_SALE_NOT_OPEN이다() throws Exception {
        var notOpened = createSchedule("now() + interval '1 hour'", "now() + interval '1 day'");
        var seats = createSeats(notOpened, 1);

        assertFailure(hold(USER, UUID.randomUUID().toString(), notOpened, seats), 409, "SALE_NOT_OPEN");
    }

    @Test
    void 공연이_시작된_회차는_409_SALE_NOT_OPEN이다() throws Exception {
        var started = createSchedule("now() - interval '2 day'", "now() - interval '1 minute'");
        var seats = createSeats(started, 1);

        assertFailure(hold(USER, UUID.randomUUID().toString(), started, seats), 409, "SALE_NOT_OPEN");
    }

    @Test
    void 보유_매수와_요청_매수의_합이_한도를_넘으면_409_PURCHASE_LIMIT_EXCEEDED이다() throws Exception {
        jdbc.update("INSERT INTO user_schedule_quotas (user_id, schedule_id, held_count, created_at, updated_at)"
                + " VALUES (?, ?, 3, now(), now())", USER, scheduleId);
        var key = UUID.randomUUID();

        var response = hold(USER, key.toString(), scheduleId, List.of(seatIds.get(0), seatIds.get(1)));

        assertFailure(response, 409, "PURCHASE_LIMIT_EXCEEDED");
        assertEquals(3, heldCount(USER));
        assertEquals(List.of("AVAILABLE", "AVAILABLE", "AVAILABLE"), seatStatuses());
        assertNoTrace(key);
    }

    @Test
    void 한도까지는_여러_번에_나눠_선점할_수_있다() throws Exception {
        assertEquals(201, hold(USER, UUID.randomUUID().toString(), scheduleId, List.of(seatIds.get(0))).status());
        assertEquals(201, hold(USER, UUID.randomUUID().toString(), scheduleId,
                List.of(seatIds.get(1), seatIds.get(2))).status());

        assertEquals(3, heldCount(USER));
    }

    @Test
    void 좌석_수와_소속이_맞지_않으면_400_SEAT_COUNT_MISMATCH이다() throws Exception {
        var otherSchedule = createSchedule("now() - interval '1 minute'", "now() + interval '1 day'");
        var otherSeat = createSeats(otherSchedule, 1).get(0);
        var tooMany = new ArrayList<>(seatIds);
        tooMany.addAll(createSeats(scheduleId, 2));

        assertFailure(hold(USER, UUID.randomUUID().toString(), scheduleId, List.of()), 400, "SEAT_COUNT_MISMATCH");
        assertFailure(hold(USER, UUID.randomUUID().toString(), scheduleId,
                List.of(seatIds.get(0), seatIds.get(0))), 400, "SEAT_COUNT_MISMATCH");
        assertFailure(hold(USER, UUID.randomUUID().toString(), scheduleId, tooMany), 400, "SEAT_COUNT_MISMATCH");
        assertFailure(hold(USER, UUID.randomUUID().toString(), scheduleId,
                List.of(seatIds.get(0), otherSeat)), 400, "SEAT_COUNT_MISMATCH");
        assertEquals(List.of("AVAILABLE", "AVAILABLE", "AVAILABLE"), seatStatuses());
    }

    @Test
    void 없는_회차는_404_NOT_FOUND이다() throws Exception {
        assertFailure(hold(USER, UUID.randomUUID().toString(), Long.MAX_VALUE, List.of(seatIds.get(0))),
                404, "NOT_FOUND");
    }

    @Test
    void 지운_회차는_404_NOT_FOUND이다() throws Exception {
        jdbc.update("UPDATE schedules SET deleted_at = now() WHERE id = ?", scheduleId);

        assertFailure(hold(USER, UUID.randomUUID().toString(), scheduleId, List.of(seatIds.get(0))),
                404, "NOT_FOUND");
    }

    @Test
    void 멱등키와_경로_형식이_틀리면_400_VALIDATION_FAILED이다() throws Exception {
        var body = body(List.of(seatIds.get(0)));
        var path = "/api/v1/schedules/" + scheduleId + "/reservations";

        assertFailure(send(request(path, body).header("X-User-Id", String.valueOf(USER))
                .header("X-User-Role", "USER")), 400, "VALIDATION_FAILED");
        assertFailure(hold(USER, "1-1-1-1-1", scheduleId, List.of(seatIds.get(0))), 400, "VALIDATION_FAILED");
        assertFailure(hold(USER, UUID.randomUUID().toString(), 0L, List.of(seatIds.get(0))), 400, "VALIDATION_FAILED");
        assertFailure(send(authorized("/api/v1/schedules/abc/reservations", body)), 400, "VALIDATION_FAILED");
        assertFailure(send(authorized(path, "{\"seatIds\":null}")), 400, "VALIDATION_FAILED");
        assertFailure(send(authorized(path, "{\"seatIds\":[0]}")), 400, "VALIDATION_FAILED");
        assertFailure(send(authorized(path, "{\"seatIds\":")), 400, "VALIDATION_FAILED");
    }

    @Test
    void Gateway_사용자_헤더가_없으면_401이다() throws Exception {
        var path = "/api/v1/schedules/" + scheduleId + "/reservations";

        assertFailure(send(request(path, body(List.of(seatIds.get(0))))
                .header("Idempotency-Key", UUID.randomUUID().toString())), 401, "UNAUTHORIZED");
    }

    private Long createSchedule(String openAt, String startsAt) {
        var venueId = jdbc.queryForObject("INSERT INTO venues (name, created_at, updated_at)"
                + " VALUES ('ISE-11 테스트 공연장', now(), now()) RETURNING id", Long.class);
        createdVenueIds.add(venueId);
        var concertId = jdbc.queryForObject("INSERT INTO concerts (title, version, created_at, updated_at)"
                + " VALUES ('ISE-11 선점 테스트', 0, now(), now()) RETURNING id", Long.class);
        return jdbc.queryForObject("INSERT INTO schedules (concert_id, venue_id, starts_at, open_at, version,"
                + " created_at, updated_at) VALUES (?, ?, " + startsAt + ", " + openAt + ", 0, now(), now())"
                + " RETURNING id", Long.class, concertId, venueId);
    }

    private List<Long> createSeats(Long scheduleId, int count) {
        var venueId = jdbc.queryForObject("SELECT venue_id FROM schedules WHERE id = ?", Long.class, scheduleId);
        var nextSeatNo = jdbc.queryForObject("SELECT count(*) + 1 FROM venue_seats WHERE venue_id = ?",
                Integer.class, venueId);
        var ids = new ArrayList<Long>();
        for (var i = 0; i < count; i++) {
            var venueSeatId = jdbc.queryForObject("INSERT INTO venue_seats (venue_id, section, row_no, seat_no, grade,"
                    + " created_at, updated_at) VALUES (?, 'A', 1, ?, 'VIP', now(), now()) RETURNING id",
                    Long.class, venueId, nextSeatNo + i);
            ids.add(jdbc.queryForObject("INSERT INTO schedule_seats (schedule_id, venue_seat_id, section, grade, price,"
                    + " status, created_at, updated_at) VALUES (?, ?, 'A', 'VIP', ?, 'AVAILABLE', now(), now())"
                    + " RETURNING id", Long.class, scheduleId, venueSeatId, SEAT_PRICE));
        }
        return ids;
    }

    private List<String> seatStatuses() {
        var statuses = new ArrayList<String>();
        for (var seatId : seatIds) {
            statuses.add(jdbc.queryForObject("SELECT status FROM schedule_seats WHERE id = ?", String.class, seatId));
        }
        return statuses;
    }

    private int heldCount(long userId) {
        return jdbc.queryForObject("SELECT COALESCE(SUM(held_count), 0) FROM user_schedule_quotas"
                + " WHERE user_id = ? AND schedule_id = ?", Integer.class, userId, scheduleId);
    }

    // 실패한 동기형 선점은 seat_hold_requests·예매 행을 남기지 않는다(ADR 0032)
    private void assertNoTrace(UUID key) {
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM seat_hold_requests WHERE request_id = ?",
                Integer.class, key));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM reservations WHERE request_id = ?",
                Integer.class, key));
    }

    private NimbusJwtDecoder paymentTokenDecoder() {
        var decoder = NimbusJwtDecoder
                .withSecretKey(new SecretKeySpec(TokenDecoders.signingKey(paymentTokenSecret), "HmacSHA256"))
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        decoder.setJwtValidator(jwt -> OAuth2TokenValidatorResult.success());
        return decoder;
    }

    private Response hold(long userId, String key, Long scheduleId, List<Long> seatIds) throws Exception {
        return send(request("/api/v1/schedules/" + scheduleId + "/reservations", body(seatIds))
                .header("X-User-Id", String.valueOf(userId))
                .header("X-User-Role", "USER")
                .header("Idempotency-Key", key));
    }

    private HttpRequest.Builder authorized(String path, String body) {
        return request(path, body)
                .header("X-User-Id", String.valueOf(USER))
                .header("X-User-Role", "USER")
                .header("Idempotency-Key", UUID.randomUUID().toString());
    }

    private HttpRequest.Builder request(String path, String body) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
    }

    private Response send(HttpRequest.Builder request) throws Exception {
        var response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        return new Response(response.statusCode(), mapper.readTree(response.body()));
    }

    private String body(List<Long> seatIds) {
        return mapper.writeValueAsString(Map.of("seatIds", seatIds));
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
