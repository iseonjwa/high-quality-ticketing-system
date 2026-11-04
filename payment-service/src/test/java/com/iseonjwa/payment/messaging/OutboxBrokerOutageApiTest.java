package com.iseonjwa.payment.messaging;

import com.iseonjwa.payment.support.AppendOnlyCleanup;
import com.iseonjwa.common.messaging.outbox.OutboxEventSender;
import com.iseonjwa.payment.pg.application.PgClient;
import com.iseonjwa.payment.pg.application.PgPaymentRef;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static com.iseonjwa.payment.support.PaymentTokenFixtures.token;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

// N10: Kafka가 죽어도 승인 API는 응답하고 Outbox가 쌓이며, 되살아나면 릴레이가 이어서 발행한다.
// Kafka 장애는 가짜 sender의 실패로 흉내 낸다. 릴레이를 실제 실행기로 돌리므로 끝나면 컨텍스트를 닫는다.
@DirtiesContext
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {"spring.config.import=",
        "spring.cloud.config.enabled=false", "eureka.client.enabled=false", "messaging.outbox.relay-enabled=true",
        "messaging.outbox.interval=100ms"})
@ActiveProfiles("test")
class OutboxBrokerOutageApiTest {
    private static final long USER = 42L;
    private static final long AMOUNT = 50000L;
    private static final long RESERVATION = 8_300_001L;

    // Note: 컨텍스트가 뜨자마자 릴레이가 돌기 시작하므로 처음부터 쓸 수 있는 가짜 sender를 빈으로 둔다
    static final class SwitchableSender implements OutboxEventSender {
        volatile boolean brokerUp;
        volatile int attempts;

        @Override
        public CompletableFuture<Void> send(String topic, String key, String payload) {
            attempts++;
            return brokerUp ? CompletableFuture.completedFuture(null)
                    : CompletableFuture.failedFuture(new RuntimeException("브로커에 닿지 않는다"));
        }
    }

    @TestConfiguration
    static class Config {
        @Bean
        @Primary
        SwitchableSender switchableSender() {
            return new SwitchableSender();
        }
    }

    @LocalServerPort
    int port;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    SwitchableSender sender;
    @MockitoBean
    PgClient pg;

    @BeforeEach
    void setUp() {
        cleanUp();
        sender.brokerUp = false;
        sender.attempts = 0;
        when(pg.approve(any())).thenReturn(new PgClient.Approved(new PgPaymentRef("pg-" + UUID.randomUUID()),
                BigDecimal.valueOf(AMOUNT), "KRW", Instant.now()));
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM idempotency_records WHERE resource_id IN (SELECT id FROM payments WHERE reservation_id = ?)",
                RESERVATION);
        jdbc.update("DELETE FROM outbox_events WHERE message_key = ?", String.valueOf(RESERVATION));
        AppendOnlyCleanup.deleteHistories(jdbc, "payment_id IN (SELECT id FROM payments WHERE reservation_id = ?)",
                RESERVATION);
        jdbc.update("DELETE FROM payments WHERE reservation_id = ?", RESERVATION);
    }

    @Test
    void Kafka가_죽어도_승인은_200이고_Outbox가_쌓이며_되살아나면_릴레이가_SENT로_바꾼다() throws Exception {
        var response = approve();

        assertEquals(200, response.statusCode());
        assertEquals("APPROVED", JsonMapper.builder().build().readTree(response.body()).get("data").get("status").asString());
        assertEquals("APPROVED", jdbc.queryForObject("SELECT status FROM payments WHERE reservation_id = ?", String.class, RESERVATION));

        // 릴레이가 실패를 겪어도 이벤트는 PENDING으로 남는다
        await(() -> sender.attempts >= 1, "릴레이가 발행을 시도해야 한다");
        assertEquals("PENDING", outboxStatus());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE message_key = ? AND event_type = 'PaymentApproved'",
                Integer.class, String.valueOf(RESERVATION)));

        // 브로커가 살아나면 다음 시도 시각을 당겨 백오프를 기다리지 않고 이어서 발행한다
        sender.brokerUp = true;
        jdbc.update("UPDATE outbox_events SET next_attempt_at = now() - interval '1 second' WHERE message_key = ? AND status = 'PENDING'",
                String.valueOf(RESERVATION));
        await(() -> "SENT".equals(outboxStatus()), "브로커가 살아나면 SENT가 되어야 한다");
        assertEquals("SENT", outboxStatus());
    }

    private String outboxStatus() {
        return jdbc.queryForObject("SELECT status FROM outbox_events WHERE message_key = ?", String.class, String.valueOf(RESERVATION));
    }

    private HttpResponse<String> approve() throws Exception {
        var body = "{\"paymentToken\":\"" + token(USER, RESERVATION, AMOUNT).build() + "\",\"amount\":" + AMOUNT
                + ",\"method\":\"CARD\"}";
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/payments"))
                .header("Content-Type", "application/json").header("Idempotency-Key", UUID.randomUUID().toString())
                .header("X-User-Id", Long.toString(USER)).header("X-User-Role", "USER")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static void await(java.util.function.BooleanSupplier condition, String message) throws InterruptedException {
        var deadline = System.nanoTime() + 5_000_000_000L;
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(20);
        assertTrue(condition.getAsBoolean(), message);
    }
}
