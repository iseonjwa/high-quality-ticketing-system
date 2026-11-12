package com.iseonjwa.payment.warmup;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.iseonjwa.payment.observability.PaymentMetrics;
import com.iseonjwa.payment.payment.application.PaymentTokenVerifier;
import com.iseonjwa.payment.payment.domain.IdempotencyRecordRepository;
import com.iseonjwa.payment.payment.domain.PaymentRepository;
import com.iseonjwa.payment.pg.infrastructure.Resilience4jPgApprovalGate;
import com.iseonjwa.payment.pg.infrastructure.Resilience4jPgRecoveryGate;
import com.iseonjwa.payment.pg.infrastructure.UnmeteredMockPg;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// 실제 DB와 로컬 가짜 Mock PG로 웜업 세 단계를 돌린다(ISE-243). 회차별 시간 로그는 원인을 가르는 첫 근거로 출력한다.
// 값은 로컬 테스트 환경 값이다. 배포 서버 값과 다르다
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {"spring.config.import=", "spring.cloud.config.enabled=false", "eureka.client.enabled=false"})
@ActiveProfiles("test")
class PaymentWarmupIntegrationTest {
    private static final int ITERATIONS = 20;
    private static final AtomicInteger PG_CALLS = new AtomicInteger();
    private static final HttpServer PG = startFakePg();

    @DynamicPropertySource
    static void pgUrl(DynamicPropertyRegistry registry) {
        registry.add("payment.pg.mock.base-url", () -> "http://127.0.0.1:" + PG.getAddress().getPort());
    }

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    TransactionTemplate tx;
    @Autowired
    IdempotencyRecordRepository idempotency;
    @Autowired
    PaymentRepository payments;
    @Autowired
    PaymentTokenVerifier tokens;
    @Autowired
    UnmeteredMockPg mockPg;
    @Autowired
    Resilience4jPgApprovalGate approvalGate;
    @Autowired
    Resilience4jPgRecoveryGate recoveryGate;

    @AfterAll
    static void stopPg() {
        PG.stop(0);
    }

    @Test
    void 웜업은_행을_만들지_않고_PG_조회는_게이트를_거치지_않으며_회차별_시간을_남긴다() {
        var before = rowCounts();
        var approveCalls = approvalGate.circuitBreaker().getMetrics().getNumberOfBufferedCalls();
        var recoveryCalls = recoveryGate.circuitBreaker().getMetrics().getNumberOfBufferedCalls();
        PG_CALLS.set(0);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        var logger = (Logger) LoggerFactory.getLogger(PaymentWarmup.class);
        logger.addAppender(appender);
        var warmup = new PaymentWarmup(List.of(
                PaymentWarmupConfiguration.dbStep(tx, idempotency, payments),
                PaymentWarmupConfiguration.tokenStep(tokens),
                PaymentWarmupConfiguration.pgStep(mockPg.client())),
                new WarmupProperties(true, ITERATIONS, null), new PaymentMetrics(new SimpleMeterRegistry()));

        try {
            warmup.start();
        } finally {
            logger.detachAppender(appender);
        }

        appender.list.forEach(event -> System.out.println("WARMUP-LOG " + event.getFormattedMessage()));
        assertEquals(before, rowCounts(), "웜업이 행을 만들었다");
        assertEquals(ITERATIONS, PG_CALLS.get());
        assertEquals(approveCalls, approvalGate.circuitBreaker().getMetrics().getNumberOfBufferedCalls());
        assertEquals(recoveryCalls, recoveryGate.circuitBreaker().getMetrics().getNumberOfBufferedCalls());
        var messages = appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
        for (var step : List.of("db", "token", "pg")) {
            assertTrue(messages.stream().anyMatch(m -> m.contains("step=" + step + " firstMs=")), step + " 로그가 없다");
        }
        assertTrue(messages.stream().anyMatch(m -> m.contains("step=total")));
    }

    private List<Integer> rowCounts() {
        return List.of("payments", "idempotency_records", "payment_status_histories", "outbox_events").stream()
                .map(table -> jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class)).toList();
    }

    private static HttpServer startFakePg() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/mock-pg/payments/", exchange -> {
                PG_CALLS.incrementAndGet();
                var bytes = "{\"code\":\"NOT_FOUND\"}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(404, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
            server.start();
            return server;
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
