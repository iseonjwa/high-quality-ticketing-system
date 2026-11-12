package com.iseonjwa.payment.payment.presentation;

import com.iseonjwa.payment.pg.application.PgClient;
import com.iseonjwa.payment.support.AppendOnlyCleanup;
import com.iseonjwa.payment.support.PgClientsTestConfig;
import org.junit.jupiter.api.AfterEach;
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

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static com.iseonjwa.payment.support.PaymentTokenFixtures.token;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

// 토스 모드에서 payment.pg.mock-bypass-enabled=false이면 Mock 건너뛰기를 받지 않는다(ISE-231).
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.config.import=", "spring.cloud.config.enabled=false", "eureka.client.enabled=false",
                "payment.pg.provider=toss", "payment.pg.toss.secret-key=test_sk_bypass_off_dummy",
                "payment.pg.toss.client-key=test_ck_bypass_off_dummy", "payment.pg.mock-bypass-enabled=false"})
@ActiveProfiles("test")
@Import(PgClientsTestConfig.class)
class PaymentTossModeBypassOffApiTest {
    private static final long USER = 62L;
    private static final long AMOUNT = 50000L;
    private static final long FIRST_RESERVATION = 8_650_000;
    private static final AtomicLong RESERVATION_IDS = new AtomicLong(FIRST_RESERVATION);

    private final HttpClient http = HttpClient.newHttpClient();

    @LocalServerPort
    int port;
    @Autowired
    JdbcTemplate jdbc;
    @MockitoBean
    PgClient pg;

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM idempotency_records WHERE resource_id IN (SELECT id FROM payments WHERE reservation_id >= ?)",
                FIRST_RESERVATION);
        jdbc.update("DELETE FROM outbox_events WHERE message_key ~ '^865[0-9]{4}$'");
        AppendOnlyCleanup.deleteHistories(jdbc, "payment_id IN (SELECT id FROM payments WHERE reservation_id >= ?)",
                FIRST_RESERVATION);
        jdbc.update("DELETE FROM payments WHERE reservation_id >= ?", FIRST_RESERVATION);
    }

    @Test
    void 건너뛰기를_끄면_pgProvider_MOCK은_400이고_행과_멱등_기록을_남기지_않는다() throws Exception {
        var reservation = RESERVATION_IDS.incrementAndGet();
        var key = UUID.randomUUID().toString();

        var response = post("/api/v1/payments", key, "{\"paymentToken\":\"" + token(USER, reservation, AMOUNT).build()
                + "\",\"amount\":" + AMOUNT + ",\"method\":\"CARD\",\"pgProvider\":\"MOCK\"}");

        assertEquals(400, response.status());
        assertEquals("VALIDATION_FAILED", response.json().get("code").asString());
        verify(pg, never()).approve(any());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM payments WHERE reservation_id = ?", Integer.class,
                reservation));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM idempotency_records WHERE idem_key = ?::uuid",
                Integer.class, key));
    }

    @Test
    void 건너뛰기를_끄면_checkout의_mockBypass는_false다() throws Exception {
        var reservation = RESERVATION_IDS.incrementAndGet();

        var response = post("/api/v1/payments/checkout", null,
                "{\"paymentToken\":\"" + token(USER, reservation, AMOUNT).build() + "\"}");

        assertEquals(200, response.status());
        assertEquals("TOSS", response.json().get("data").get("provider").asString());
        assertFalse(response.json().get("data").get("mockBypass").asBoolean());
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
