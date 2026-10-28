package com.iseonjwa.payment.payment.presentation;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
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
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// 결제 조회 API의 접근 제어 매트릭스(0021, N9). 결제 행은 DB에 직접 넣어 승인 흐름과 독립으로 확인한다.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.config.import=", "spring.cloud.config.enabled=false", "eureka.client.enabled=false"})
@ActiveProfiles("test")
class PaymentQueryApiTest {
    private static final long OWNER = 51L;
    private static final long OTHER = 52L;
    private static final long FIRST_RESERVATION = 8_500_000;
    private static final long LAST_RESERVATION = 8_599_999;
    private static final AtomicLong RESERVATION_IDS = new AtomicLong(FIRST_RESERVATION);

    private final HttpClient http = HttpClient.newHttpClient();
    private final ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> logs = new ListAppender<>();
    private final Logger serviceLogger = (Logger) LoggerFactory.getLogger("com.iseonjwa.payment.payment.application.PaymentQueryService");

    @LocalServerPort
    int port;
    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void captureLogs() {
        logs.start();
        serviceLogger.addAppender(logs);
    }

    @AfterEach
    void cleanUp() {
        serviceLogger.detachAppender(logs);
        jdbc.update("DELETE FROM payments WHERE reservation_id BETWEEN ? AND ?", FIRST_RESERVATION, LAST_RESERVATION);
    }

    @Test
    void 소유자는_승인된_결제를_조회한다() throws Exception {
        var id = insert(OWNER, "APPROVED", "pg_tx_id, approved_at, pg_approved_amount", "'pg-" + UUID.randomUUID() + "', now(), 50000");

        var response = get(id, OWNER, "USER");

        assertEquals(200, response.status());
        var data = response.json().get("data");
        assertEquals(id, data.get("paymentId").asLong());
        assertEquals("APPROVED", data.get("status").asString());
        assertEquals(50000, data.get("amount").asLong());
        assertFalse(data.get("approvedAt").isNull());
        assertTrue(data.get("refundedAt").isNull());
    }

    @Test
    void 환불된_결제는_환불_시각을_내보낸다() throws Exception {
        var id = insert(OWNER, "REFUNDED", "pg_tx_id, refunded_at", "'pg-" + UUID.randomUUID() + "', now()");

        var data = get(id, OWNER, "USER").json().get("data");

        assertEquals("REFUNDED", data.get("status").asString());
        assertFalse(data.get("refundedAt").isNull());
    }

    @Test
    void 확정되지_않은_결제와_수동_확인_결제도_상태를_보여_준다() throws Exception {
        var requested = insert(OWNER, "REQUESTED", "next_check_at", "now() + interval '10 seconds'");
        var manual = insert(OWNER, "NEEDS_MANUAL_CHECK", "", null);

        assertEquals("REQUESTED", get(requested, OWNER, "USER").json().get("data").get("status").asString());
        var manualData = get(manual, OWNER, "USER").json().get("data");
        assertEquals("NEEDS_MANUAL_CHECK", manualData.get("status").asString());
        assertTrue(manualData.get("approvedAt").isNull());
    }

    @Test
    void 타인의_결제는_없는_결제와_같은_404이고_본문이_같다() throws Exception {
        var othersId = insert(OTHER, "REQUESTED", "next_check_at", "now() + interval '10 seconds'");
        var missingId = othersId + 1_000_000;

        var others = get(othersId, OWNER, "USER");
        var missing = get(missingId, OWNER, "USER");

        assertEquals(404, others.status());
        assertEquals(404, missing.status());
        assertEquals("NOT_FOUND", others.json().get("code").asString());
        assertEquals(withoutTraceId(missing), withoutTraceId(others));
        assertFalse(others.body().contains(Long.toString(othersId)), "응답에 결제 ID가 들어가면 안 된다");
    }

    @Test
    void 타인의_결제_접근은_WARN으로_남고_없는_결제는_남기지_않는다() throws Exception {
        var othersId = insert(OTHER, "REQUESTED", "next_check_at", "now() + interval '10 seconds'");

        get(othersId, OWNER, "USER");
        var warns = logs.list.stream().filter(e -> e.getLevel() == Level.WARN).toList();
        assertEquals(1, warns.size());
        var message = warns.get(0).getFormattedMessage();
        assertTrue(message.contains("userId=" + OWNER) && message.contains("paymentId=" + othersId), message);

        logs.list.clear();
        get(othersId + 1_000_000, OWNER, "USER");
        assertTrue(logs.list.stream().noneMatch(e -> e.getLevel() == Level.WARN));
    }

    @Test
    void ADMIN은_타인의_결제도_조회하고_없는_결제는_404다() throws Exception {
        var id = insert(OTHER, "REQUESTED", "next_check_at", "now() + interval '10 seconds'");

        assertEquals(200, get(id, OWNER, "ADMIN").status());
        assertEquals(404, get(id + 1_000_000, OWNER, "ADMIN").status());
    }

    @Test
    void 인증_헤더가_없으면_401이다() throws Exception {
        var id = insert(OWNER, "NEEDS_MANUAL_CHECK", "", null);

        assertEquals(401, get(id, null, null).status());
    }

    @Test
    void 숫자가_아닌_결제_ID는_400이다() throws Exception {
        assertEquals(400, getRaw("abc", OWNER, "USER").status());
    }

    private long insert(long userId, String status, String extraColumns, String extraValues) {
        var reservationId = RESERVATION_IDS.incrementAndGet();
        var columns = extraColumns.isEmpty() ? "" : ", " + extraColumns;
        var values = extraValues == null ? "" : ", " + extraValues;
        return jdbc.queryForObject("""
                INSERT INTO payments (payment_no, reservation_id, user_id, amount, method, status, pg_order_id,
                                     created_at, updated_at%s)
                VALUES (?, ?, ?, 50000, 'CARD', '%s', ?, now(), now()%s) RETURNING id""".formatted(columns, status, values),
                Long.class, "PAY-" + reservationId + "-" + UUID.randomUUID().toString().substring(0, 8), reservationId, userId,
                UUID.randomUUID());
    }

    private String withoutTraceId(Response response) {
        var node = (tools.jackson.databind.node.ObjectNode) response.json();
        node.remove("traceId");
        return node.toString();
    }

    private Response get(long paymentId, Long userId, String role) throws Exception {
        return getRaw(Long.toString(paymentId), userId, role);
    }

    private Response getRaw(String paymentId, Long userId, String role) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/payments/" + paymentId)).GET();
        if (userId != null) request.header("X-User-Id", Long.toString(userId)).header("X-User-Role", role);
        var response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        return new Response(response.statusCode(), response.body());
    }

    private record Response(int status, String body) {
        JsonNode json() {
            return JsonMapper.builder().build().readTree(body);
        }
    }
}
