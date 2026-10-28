package com.iseonjwa.payment.payment.presentation;

import com.iseonjwa.payment.payment.application.PaymentTx;
import com.iseonjwa.payment.pg.application.PgClient;
import com.iseonjwa.payment.pg.application.PgPaymentRef;
import com.iseonjwa.payment.pg.application.UnknownCause;
import com.iseonjwa.payment.pg.infrastructure.Resilience4jPgApprovalGate;
import com.iseonjwa.payment.pg.infrastructure.Resilience4jPgRecoveryGate;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static com.iseonjwa.payment.support.PaymentTokenFixtures.token;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// PG 호출 보호(ISE-37, 0018)를 HTTP로 확인한다(E10, N4, N5, N29). 승인 그룹은 동시 2, 최근 4회 중 50%면 OPEN으로 줄여 둔다.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.config.import=", "spring.cloud.config.enabled=false", "eureka.client.enabled=false",
                "payment.pg.protection.approve.max-concurrent-calls=2",
                "payment.pg.protection.approve.sliding-window-size=4",
                "payment.pg.protection.approve.minimum-number-of-calls=4",
                "payment.pg.protection.approve.failure-rate-threshold=50",
                "payment.pg.protection.approve.open-duration=60s"})
@ActiveProfiles("test")
class PgProtectionApiTest {
    private static final long USER = 52L;
    private static final long AMOUNT = 50000L;
    private static final long FIRST_RESERVATION = 8_400_000;
    private static final AtomicLong RESERVATION_IDS = new AtomicLong(FIRST_RESERVATION);

    private final HttpClient http = HttpClient.newHttpClient();

    @LocalServerPort
    int port;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    Resilience4jPgApprovalGate approvalGate;
    @Autowired
    Resilience4jPgRecoveryGate recoveryGate;
    @MockitoBean
    PgClient pg;
    @MockitoSpyBean
    PaymentTx paymentTx;

    @BeforeEach
    void resetState() {
        reset(pg, paymentTx);
        approvalGate.circuitBreaker().reset();
        recoveryGate.circuitBreaker().reset();
        when(pg.approve(any())).thenAnswer(invocation -> approved());
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM idempotency_record WHERE resource_id IN (SELECT id FROM payment WHERE reservation_id >= ?)",
                FIRST_RESERVATION);
        jdbc.update("DELETE FROM outbox WHERE message_key ~ '^84[0-9]{5}$'");
        jdbc.update("DELETE FROM payment_status_history WHERE payment_id IN (SELECT id FROM payment WHERE reservation_id >= ?)",
                FIRST_RESERVATION);
        jdbc.update("DELETE FROM payment WHERE reservation_id >= ?", FIRST_RESERVATION);
    }

    @Test
    void CB가_열리면_신규_승인은_503이고_행과_멱등_기록이_남지_않는다() throws Exception {
        when(pg.approve(any())).thenReturn(new PgClient.Unknown(UnknownCause.TIMEOUT));
        for (var i = 0; i < 4; i++) approve(key(), newReservation());
        assertEquals(CircuitBreaker.State.OPEN, approvalGate.circuitBreaker().getState());
        var blocked = newReservation();
        var blockedKey = key();

        var response = approve(blockedKey, blocked);

        assertEquals(503, response.status());
        assertEquals("PG_UNAVAILABLE", response.json().get("code").asString());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM payment WHERE reservation_id = ?", Integer.class, blocked));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM idempotency_record WHERE idem_key = ?::uuid",
                Integer.class, blockedKey), "허가를 못 얻으면 멱등 기록도 남기지 않는다");
        verify(pg, times(4)).approve(any());
    }

    @Test
    void CB가_열려도_같은_키_재요청과_기존_결제_응답은_PG를_부르지_않고_그대로_나간다() throws Exception {
        when(pg.approve(any())).thenReturn(new PgClient.Unknown(UnknownCause.TIMEOUT));
        var reservation = newReservation();
        var key = key();
        var first = approve(key, reservation);
        for (var i = 0; i < 3; i++) approve(key(), newReservation());
        assertEquals(CircuitBreaker.State.OPEN, approvalGate.circuitBreaker().getState());

        var sameKey = approve(key, reservation);
        var newKey = approve(key(), reservation);

        assertEquals(202, first.status());
        assertEquals(202, sameKey.status());
        assertEquals(first.json().get("data").get("paymentId").asLong(), sameKey.json().get("data").get("paymentId").asLong());
        assertEquals(202, newKey.status());
        verify(pg, times(4)).approve(any());
    }

    @Test
    void 허가를_얻은_뒤_TX1이_실패하면_503이고_허가는_결과로_세지_않고_반환된다() throws Exception {
        doThrow(new DataAccessResourceFailureException("DB 장애")).when(paymentTx).createRequested(any(), any(), any());

        var response = approve(key(), newReservation());

        assertEquals(503, response.status());
        assertEquals("SERVICE_UNAVAILABLE", response.json().get("code").asString());
        assertEquals(2, approvalGate.bulkhead().getMetrics().getAvailableConcurrentCalls());
        assertEquals(0, approvalGate.circuitBreaker().getMetrics().getNumberOfBufferedCalls());
        verify(pg, times(0)).approve(any());
    }

    @Test
    void 승인_호출이_가득_차면_추가_요청은_바로_503이고_복구_호출은_막히지_않는다() throws Exception {
        var inFlight = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        when(pg.approve(any())).thenAnswer(invocation -> {
            inFlight.countDown();
            release.await(10, TimeUnit.SECONDS);
            return approved();
        });
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> approve(key(), newReservation()));
            var second = executor.submit(() -> approve(key(), newReservation()));
            assertTrue(inFlight.await(10, TimeUnit.SECONDS), "두 요청이 PG 호출 중이어야 한다");
            var overloadedReservation = newReservation();

            var overloaded = approve(key(), overloadedReservation);
            var recovery = recoveryGate.execute(() -> "복구 조회", result -> false);

            assertEquals(503, overloaded.status());
            assertEquals("PG_UNAVAILABLE", overloaded.json().get("code").asString());
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM payment WHERE reservation_id = ?", Integer.class,
                    overloadedReservation));
            assertTrue(recovery.isPresent(), "신규 승인 과부하가 복구 호출을 막지 않는다");
            release.countDown();
            assertEquals(200, first.get(10, TimeUnit.SECONDS).status());
            assertEquals(200, second.get(10, TimeUnit.SECONDS).status());
            assertEquals(2, approvalGate.bulkhead().getMetrics().getAvailableConcurrentCalls(), "허가가 모두 반환된다");
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void 클라이언트가_먼저_포기해도_서버_처리는_계속되고_같은_키_재시도가_결과를_받는다() throws Exception {
        // Gateway 504와 같은 상황이다. 서버는 응답 시간이 지나도 PG 호출과 TX2를 끝낸다
        var reservation = newReservation();
        var key = key();
        var calls = new AtomicInteger();
        when(pg.approve(any())).thenAnswer(invocation -> {
            calls.incrementAndGet();
            Thread.sleep(1500);
            return approved();
        });

        assertThrows(HttpTimeoutException.class, () -> post(key, tokenFor(reservation), 500));
        var settled = waitForStatus(reservation, "APPROVED");
        var retry = approve(key, reservation);

        assertTrue(settled, "클라이언트가 끊어도 서버가 승인까지 끝낸다");
        assertEquals(200, retry.status());
        assertEquals("APPROVED", retry.json().get("data").get("status").asString());
        assertEquals(1, calls.get(), "재시도는 PG를 다시 부르지 않는다");
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM payment WHERE reservation_id = ?", Integer.class, reservation));
    }

    private boolean waitForStatus(long reservation, String status) throws InterruptedException {
        var deadline = System.nanoTime() + 8_000_000_000L;
        while (System.nanoTime() < deadline) {
            var current = jdbc.queryForList("SELECT status FROM payment WHERE reservation_id = ?", String.class, reservation);
            if (!current.isEmpty() && current.get(0).equals(status)) return true;
            Thread.sleep(50);
        }
        return false;
    }

    private long newReservation() {
        return RESERVATION_IDS.incrementAndGet();
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }

    // 같은 키로 재요청할 때는 같은 토큰을 보내야 같은 본문이다. 토큰은 발급마다 달라서 예매별로 한 번만 만든다
    private final java.util.Map<Long, String> tokens = new java.util.concurrent.ConcurrentHashMap<>();

    private String tokenFor(long reservation) {
        return tokens.computeIfAbsent(reservation, id -> token(USER, id, AMOUNT).build());
    }

    private static PgClient.Approved approved() {
        return new PgClient.Approved(new PgPaymentRef("pg-" + UUID.randomUUID()), BigDecimal.valueOf(AMOUNT), "KRW",
                Instant.now());
    }

    private Response approve(String key, long reservation) throws Exception {
        return post(key, tokenFor(reservation), 15_000);
    }

    private Response post(String key, String token, long timeoutMillis) throws Exception {
        var body = "{\"paymentToken\":\"" + token + "\",\"amount\":" + AMOUNT + ",\"method\":\"CARD\"}";
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/payments"))
                .timeout(Duration.ofMillis(timeoutMillis))
                .header("Content-Type", "application/json").header("Idempotency-Key", key)
                .header("X-User-Id", Long.toString(USER)).header("X-User-Role", "USER")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        var response = http.send(request, HttpResponse.BodyHandlers.ofString());
        return new Response(response.statusCode(), response.body());
    }

    private record Response(int status, String body) {
        JsonNode json() {
            return JsonMapper.builder().build().readTree(body);
        }
    }
}
