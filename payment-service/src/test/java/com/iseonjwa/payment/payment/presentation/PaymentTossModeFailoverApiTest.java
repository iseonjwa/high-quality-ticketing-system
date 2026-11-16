package com.iseonjwa.payment.payment.presentation;

import com.iseonjwa.payment.payment.domain.PgProvider;
import com.iseonjwa.payment.pg.application.PgClient;
import com.iseonjwa.payment.pg.application.PgPaymentRef;
import com.iseonjwa.payment.pg.infrastructure.Resilience4jPgApprovalGate;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static com.iseonjwa.payment.support.PaymentTokenFixtures.token;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// 토스 모드에서 건너뛰기는 끄고 폴백만 켠 서버(기본값)다. 토스가 막혀도 Mock으로 승인되고 checkout이 그 사실을 알린다(ISE-305).
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.config.import=", "spring.cloud.config.enabled=false", "eureka.client.enabled=false",
                "payment.pg.provider=toss", "payment.pg.toss.secret-key=test_sk_failover_dummy",
                "payment.pg.toss.client-key=test_ck_failover_dummy", "payment.pg.mock-bypass-enabled=false"})
@ActiveProfiles("test")
@Import(PgClientsTestConfig.class)
class PaymentTossModeFailoverApiTest {
    private static final long USER = 63L;
    private static final long AMOUNT = 50000L;
    private static final long FIRST_RESERVATION = 8_700_000;
    private static final AtomicLong RESERVATION_IDS = new AtomicLong(FIRST_RESERVATION);

    private final HttpClient http = HttpClient.newHttpClient();

    @LocalServerPort
    int port;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    Resilience4jPgApprovalGate approvalGate;
    @Autowired
    StubPgClients pgClients;
    @MockitoBean
    PgClient pg;
    PgClient mockPg;

    @BeforeEach
    void stubPg() {
        reset(pg);
        mockPg = mock(PgClient.class);
        pgClients.route(PgProvider.MOCK, mockPg);
        when(mockPg.approve(any())).thenAnswer(invocation -> new PgClient.Approved(
                new PgPaymentRef("mock-tx-" + System.nanoTime()), BigDecimal.valueOf(AMOUNT), "KRW", Instant.now()));
        approvalGate.circuitBreaker(PgProvider.TOSS).reset();
        approvalGate.circuitBreaker(PgProvider.MOCK).reset();
    }

    @AfterEach
    void cleanUp() {
        approvalGate.circuitBreaker(PgProvider.TOSS).reset();
        pgClients.reset();
        jdbc.update("DELETE FROM idempotency_records WHERE resource_id IN (SELECT id FROM payments WHERE reservation_id >= ?)",
                FIRST_RESERVATION);
        jdbc.update("DELETE FROM outbox_events WHERE message_key ~ '^870[0-9]{4}$'");
        AppendOnlyCleanup.deleteHistories(jdbc, "payment_id IN (SELECT id FROM payments WHERE reservation_id >= ?)",
                FIRST_RESERVATION);
        jdbc.update("DELETE FROM payments WHERE reservation_id >= ?", FIRST_RESERVATION);
    }

    @Test
    void 건너뛰기를_꺼도_폴백이_켜져_있으면_pgProvider_MOCK으로_승인한다() throws Exception {
        var reservation = RESERVATION_IDS.incrementAndGet();

        var response = approve(reservation, "MOCK");

        assertEquals(200, response.status());
        assertEquals("MOCK", jdbc.queryForObject("SELECT pg_provider FROM payments WHERE reservation_id = ?",
                String.class, reservation));
        verify(mockPg).approve(any());
        verify(pg, never()).approve(any());
    }

    @Test
    void 토스_차단기가_열려도_pgProvider_MOCK_승인은_받고_토스_승인은_503이다() throws Exception {
        approvalGate.circuitBreaker(PgProvider.TOSS).transitionToOpenState();
        var mockReservation = RESERVATION_IDS.incrementAndGet();
        var tossReservation = RESERVATION_IDS.incrementAndGet();

        assertEquals(200, approve(mockReservation, "MOCK").status());
        var toss = approve(tossReservation, null);

        assertEquals(503, toss.status());
        assertEquals("PG_UNAVAILABLE", toss.json().get("code").asString());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM payments WHERE reservation_id = ?", Integer.class,
                tossReservation), "허가 단계 거절은 행을 남기지 않는다");
        verify(pg, never()).approve(any());
    }

    @Test
    void checkout은_폴백_PG와_토스_가용_여부를_알린다() throws Exception {
        var open = RESERVATION_IDS.incrementAndGet();

        var healthy = checkout(open).json().get("data");
        assertEquals("MOCK", healthy.get("fallbackProvider").asString());
        assertTrue(healthy.get("primaryAvailable").asBoolean());

        approvalGate.circuitBreaker(PgProvider.TOSS).transitionToOpenState();
        var blocked = checkout(open).json().get("data");
        assertEquals("TOSS", blocked.get("provider").asString());
        assertEquals("MOCK", blocked.get("fallbackProvider").asString());
        assertFalse(blocked.get("primaryAvailable").asBoolean());
    }

    @Test
    void Mock_차단기가_열려도_토스의_primaryAvailable은_true다() throws Exception {
        approvalGate.circuitBreaker(PgProvider.MOCK).transitionToOpenState();

        var data = checkout(RESERVATION_IDS.incrementAndGet()).json().get("data");

        assertTrue(data.get("primaryAvailable").asBoolean());
    }

    private Response approve(long reservation, String pgProvider) throws Exception {
        var provider = pgProvider == null ? "" : ",\"pgProvider\":\"" + pgProvider + "\"";
        var ref = pgProvider == null ? ",\"clientPaymentRef\":\"tgen_failover\"" : "";
        return post("/api/v1/payments", UUID.randomUUID().toString(), "{\"paymentToken\":\""
                + token(USER, reservation, AMOUNT).build() + "\",\"amount\":" + AMOUNT + ",\"method\":\"CARD\"" + ref
                + provider + "}");
    }

    private Response checkout(long reservation) throws Exception {
        return post("/api/v1/payments/checkout", null,
                "{\"paymentToken\":\"" + token(USER, reservation, AMOUNT).build() + "\"}");
    }

    private Response post(String path, String key, String body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Content-Type", "application/json").header("X-User-Id", Long.toString(USER))
                .header("X-User-Role", "USER").POST(HttpRequest.BodyPublishers.ofString(body));
        if (key != null) request.header("Idempotency-Key", key);
        var response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        return new Response(response.statusCode(), response.body());
    }

    private record Response(int status, String body) {
        JsonNode json() {
            return JsonMapper.builder().build().readTree(body);
        }
    }
}
