package com.iseonjwa.payment.billing;

import com.iseonjwa.payment.billing.application.BillingKeyEncryptor;
import com.iseonjwa.payment.payment.application.BillingPaymentCommand;
import com.iseonjwa.payment.payment.application.BillingPaymentService;
import com.iseonjwa.payment.payment.domain.DeclineReason;
import com.iseonjwa.payment.pg.application.PgClient;
import com.iseonjwa.payment.pg.application.PgPaymentRef;
import com.iseonjwa.payment.pg.application.UnknownCause;
import com.iseonjwa.payment.pg.infrastructure.Resilience4jPgApprovalGate;
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

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// 빌링키 등록·해지 API와 추첨 자동결제(ISE-96). PG는 PgClient 포트를 대체해 결과를 정한다.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.config.import=", "spring.cloud.config.enabled=false", "eureka.client.enabled=false"})
@ActiveProfiles("test")
class BillingKeyAndPaymentTest {
    private static final long FIRST_USER = 9_100_000;
    private static final long LAST_USER = 9_199_999;
    private static final long FIRST_RESERVATION = 8_300_000;
    private static final long LAST_RESERVATION = 8_399_999;
    private static final AtomicLong USERS = new AtomicLong(FIRST_USER);
    private static final AtomicLong RESERVATIONS = new AtomicLong(FIRST_RESERVATION);
    private static final BigDecimal AMOUNT = new BigDecimal("121000");

    private final HttpClient http = HttpClient.newHttpClient();

    @LocalServerPort
    int port;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    BillingPaymentService billingPayments;
    @Autowired
    BillingKeyEncryptor encryptor;
    @Autowired
    Resilience4jPgApprovalGate approvalGate;
    @MockitoBean
    PgClient pg;

    @BeforeEach
    void stubPg() {
        reset(pg);
        approvalGate.circuitBreaker().reset();
        when(pg.issueBillingKey(anyString(), anyString()))
                .thenAnswer(invocation -> new PgClient.BillingKeyIssued("bk-plain-" + USERS.get(), "4242"));
        when(pg.approveBilling(any(), anyString())).thenAnswer(invocation -> new PgClient.Approved(
                new PgPaymentRef("pg-" + System.nanoTime()), AMOUNT, "KRW", Instant.now()));
    }

    @AfterEach
    void cleanUp() {
        assertEquals(10, approvalGate.bulkhead().getMetrics().getAvailableConcurrentCalls(), "Bulkhead 허가가 새고 있다");
        jdbc.update("DELETE FROM billing_keys WHERE user_id BETWEEN ? AND ?", FIRST_USER, LAST_USER);
        jdbc.update("DELETE FROM outbox_events WHERE message_key ~ '^83[0-9]{5}$'");
        jdbc.update("DELETE FROM payment_status_histories WHERE payment_id IN (SELECT id FROM payments"
                + " WHERE reservation_id BETWEEN ? AND ?)", FIRST_RESERVATION, LAST_RESERVATION);
        jdbc.update("DELETE FROM payments WHERE reservation_id BETWEEN ? AND ?", FIRST_RESERVATION, LAST_RESERVATION);
    }

    // 완료 기준: 등록하면 ACTIVE 1개, 원문은 저장하지 않는다
    @Test
    void 등록하면_201과_ACTIVE_빌링키를_암호화해_저장한다() throws Exception {
        long user = USERS.incrementAndGet();

        var response = register(user, "auth-1");

        assertEquals(201, response.status());
        assertEquals("4242", response.json().get("data").get("cardLast4").asString());
        assertEquals("ACTIVE", response.json().get("data").get("status").asString());
        assertFalse(response.body().contains("bk-plain"), "응답에 빌링키를 담지 않는다");
        var stored = jdbc.queryForObject("SELECT billing_key_enc FROM billing_keys WHERE user_id = ? AND status = 'ACTIVE'",
                String.class, user);
        assertFalse(stored.contains("bk-plain"));
        assertEquals("bk-plain-" + user, encryptor.decrypt(stored));
    }

    // 완료 기준: 다시 등록하면 기존 것은 REVOKED
    @Test
    void 다시_등록하면_기존_키는_REVOKED이고_ACTIVE는_하나다() throws Exception {
        long user = USERS.incrementAndGet();
        register(user, "auth-1");

        assertEquals(201, register(user, "auth-2").status());

        assertEquals(1, count("SELECT count(*) FROM billing_keys WHERE user_id = ? AND status = 'ACTIVE'", user));
        assertEquals(1, count("SELECT count(*) FROM billing_keys WHERE user_id = ? AND status = 'REVOKED'", user));
    }

    @Test
    void PG가_인증_값을_거절하면_400이고_결과를_모르면_503이며_저장하지_않는다() throws Exception {
        long user = USERS.incrementAndGet();
        when(pg.issueBillingKey(eq("REJECT-1"), anyString())).thenReturn(new PgClient.BillingKeyRejected());
        when(pg.issueBillingKey(eq("auth-timeout"), anyString())).thenReturn(new PgClient.Unknown(UnknownCause.TIMEOUT));

        var rejected = register(user, "REJECT-1");
        var unknown = register(user, "auth-timeout");

        assertEquals(400, rejected.status());
        assertEquals("BILLING_KEY_REJECTED", rejected.json().get("code").asString());
        assertEquals(503, unknown.status());
        assertEquals(0, count("SELECT count(*) FROM billing_keys WHERE user_id = ?", user));
    }

    @Test
    void 해지하면_REVOKED이고_다시_해지해도_성공이다() throws Exception {
        long user = USERS.incrementAndGet();
        register(user, "auth-1");

        assertEquals(200, revoke(user).status());
        assertEquals(200, revoke(user).status());

        assertEquals(0, count("SELECT count(*) FROM billing_keys WHERE user_id = ? AND status = 'ACTIVE'", user));
    }

    @Test
    void 빌링키로_자동결제하면_BILLING_결제가_승인되고_결과_이벤트가_나간다() throws Exception {
        long user = USERS.incrementAndGet();
        register(user, "auth-1");
        long reservation = RESERVATIONS.incrementAndGet();

        var payment = billingPayments.approve(new BillingPaymentCommand(reservation, user, AMOUNT, "KRW"));

        assertEquals("APPROVED", payment.getStatus().name());
        assertEquals("BILLING", payment.getMethod().name());
        verify(pg).approveBilling(any(), eq("bk-plain-" + user));
        assertEquals(1, count("SELECT count(*) FROM outbox_events WHERE message_key = ? AND event_type = 'PaymentApproved'",
                String.valueOf(reservation)));
    }

    // 추첨 D4·D12: 빌링키가 없으면 PG를 부르지 않고 거절해 payment.result로 알린다(당첨자 책임이라 승계)
    @Test
    void 빌링키가_없으면_PG를_부르지_않고_BILLING_KEY_MISSING으로_거절한다() {
        long user = USERS.incrementAndGet();
        long reservation = RESERVATIONS.incrementAndGet();

        var payment = billingPayments.approve(new BillingPaymentCommand(reservation, user, AMOUNT, "KRW"));

        assertEquals("DECLINED", payment.getStatus().name());
        assertEquals(DeclineReason.BILLING_KEY_MISSING, payment.getDeclineCode());
        verify(pg, never()).approveBilling(any(), anyString());
        assertEquals(1, count("SELECT count(*) FROM outbox_events WHERE message_key = ? AND event_type = 'PaymentDeclined'"
                + " AND payload::text LIKE '%BILLING_KEY_MISSING%'", String.valueOf(reservation)));
    }

    // 완료 기준: 같은 reservationId 요청 2번 → 결제 1건(outbox 릴레이 재발행)
    @Test
    void 같은_예매로_두_번_요청해도_결제는_한_건이고_PG는_한_번만_부른다() throws Exception {
        long user = USERS.incrementAndGet();
        register(user, "auth-1");
        long reservation = RESERVATIONS.incrementAndGet();
        var command = new BillingPaymentCommand(reservation, user, AMOUNT, "KRW");

        var first = billingPayments.approve(command);
        var second = billingPayments.approve(command);

        assertEquals(first.getId(), second.getId());
        verify(pg, times(1)).approveBilling(any(), anyString());
        assertEquals(1, count("SELECT count(*) FROM payments WHERE reservation_id = ?", reservation));
    }

    @Test
    void PG가_거절하면_DECLINED이고_결과를_모르면_UNKNOWN으로_재확인에_넘긴다() throws Exception {
        long user = USERS.incrementAndGet();
        register(user, "auth-1");
        long declinedReservation = RESERVATIONS.incrementAndGet();
        long unknownReservation = RESERVATIONS.incrementAndGet();
        when(pg.approveBilling(any(), anyString()))
                .thenReturn(new PgClient.Declined(DeclineReason.CARD_DECLINED))
                .thenReturn(new PgClient.Unknown(UnknownCause.TIMEOUT));

        var declined = billingPayments.approve(new BillingPaymentCommand(declinedReservation, user, AMOUNT, "KRW"));
        var unknown = billingPayments.approve(new BillingPaymentCommand(unknownReservation, user, AMOUNT, "KRW"));

        assertEquals("DECLINED", declined.getStatus().name());
        assertEquals(DeclineReason.CARD_DECLINED, declined.getDeclineCode());
        assertEquals("UNKNOWN", unknown.getStatus().name());
    }

    private int count(String sql, Object arg) {
        return jdbc.queryForObject(sql, Integer.class, arg);
    }

    private Response register(long userId, String authKey) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/payments/billing-keys"))
                .header("Content-Type", "application/json")
                .header("X-User-Id", Long.toString(userId)).header("X-User-Role", "USER")
                .POST(HttpRequest.BodyPublishers.ofString("{\"authKey\":\"" + authKey + "\"}"));
        var response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        return new Response(response.statusCode(), response.body());
    }

    private Response revoke(long userId) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/payments/billing-keys/me"))
                .header("X-User-Id", Long.toString(userId)).header("X-User-Role", "USER")
                .DELETE();
        var response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        return new Response(response.statusCode(), response.body());
    }

    private record Response(int status, String body) {
        JsonNode json() {
            return JsonMapper.builder().build().readTree(body);
        }
    }
}
