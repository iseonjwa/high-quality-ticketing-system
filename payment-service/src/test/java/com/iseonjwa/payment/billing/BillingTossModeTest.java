package com.iseonjwa.payment.billing;

import com.iseonjwa.payment.payment.application.BillingPaymentCommand;
import com.iseonjwa.payment.payment.application.BillingPaymentService;
import com.iseonjwa.payment.payment.domain.PgProvider;
import com.iseonjwa.payment.pg.application.PgClient;
import com.iseonjwa.payment.pg.application.PgPaymentRef;
import com.iseonjwa.payment.support.AppendOnlyCleanup;
import com.iseonjwa.payment.support.PgClientsTestConfig;
import com.iseonjwa.payment.support.StubPgClients;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

// 토스 모드에서도 빌링(추첨 자동결제)은 Mock으로 간다(ISE-231, ADR 0023 2026-11-10 개정). Mock은 pg, 토스는 따로 만든 목이다.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.config.import=", "spring.cloud.config.enabled=false", "eureka.client.enabled=false",
                "payment.pg.provider=toss", "payment.pg.toss.secret-key=test_sk_billing_dummy",
                "payment.pg.toss.client-key=test_ck_billing_dummy"})
@ActiveProfiles("test")
@Import(PgClientsTestConfig.class)
class BillingTossModeTest {
    private static final long FIRST_USER = 9_300_000;
    private static final long LAST_USER = 9_399_999;
    private static final long FIRST_RESERVATION = 8_800_000;
    private static final long LAST_RESERVATION = 8_899_999;
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
    StubPgClients pgClients;
    @MockitoBean
    PgClient pg;
    PgClient tossPg;

    @BeforeEach
    void stubPg() {
        reset(pg);
        tossPg = mock(PgClient.class);
        pgClients.route(PgProvider.TOSS, tossPg);
        when(pg.issueBillingKey(anyString(), anyString()))
                .thenReturn(new PgClient.BillingKeyIssued("bk-plain", "4242"));
        when(pg.approveBilling(any(), anyString())).thenAnswer(invocation -> new PgClient.Approved(
                new PgPaymentRef("pg-" + System.nanoTime()), AMOUNT, "KRW", Instant.now()));
    }

    @AfterEach
    void cleanUp() {
        pgClients.reset();
        jdbc.update("DELETE FROM billing_keys WHERE user_id BETWEEN ? AND ?", FIRST_USER, LAST_USER);
        jdbc.update("DELETE FROM outbox_events WHERE message_key ~ '^88[0-9]{5}$'");
        AppendOnlyCleanup.deleteHistories(jdbc, "payment_id IN (SELECT id FROM payments"
                + " WHERE reservation_id BETWEEN ? AND ?)", FIRST_RESERVATION, LAST_RESERVATION);
        jdbc.update("DELETE FROM payments WHERE reservation_id BETWEEN ? AND ?", FIRST_RESERVATION, LAST_RESERVATION);
    }

    @Test
    void 토스_모드에서도_빌링키_발급은_Mock으로_가고_토스는_부르지_않는다() throws Exception {
        long user = USERS.incrementAndGet();

        assertEquals(201, register(user, "auth-1"));

        verify(pg).issueBillingKey(anyString(), anyString());
        verifyNoInteractions(tossPg);
    }

    @Test
    void 토스_모드에서도_자동_승인은_Mock으로_가고_결제는_MOCK으로_남는다() throws Exception {
        long user = USERS.incrementAndGet();
        register(user, "auth-1");
        long reservation = RESERVATIONS.incrementAndGet();

        var payment = billingPayments.approve(new BillingPaymentCommand(reservation, user, AMOUNT, "KRW"));

        assertEquals("APPROVED", payment.getStatus().name());
        assertEquals(PgProvider.MOCK, payment.getPgProvider());
        assertEquals("MOCK", jdbc.queryForObject("SELECT pg_provider FROM payments WHERE reservation_id = ?",
                String.class, reservation));
        verify(pg).approveBilling(any(), anyString());
        verifyNoInteractions(tossPg);
    }

    private int register(long userId, String authKey) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/payments/billing-keys"))
                .header("Content-Type", "application/json")
                .header("X-User-Id", Long.toString(userId)).header("X-User-Role", "USER")
                .POST(HttpRequest.BodyPublishers.ofString("{\"authKey\":\"" + authKey + "\"}"));
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString()).statusCode();
    }
}
