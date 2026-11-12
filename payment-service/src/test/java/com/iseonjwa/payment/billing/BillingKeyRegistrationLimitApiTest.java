package com.iseonjwa.payment.billing;

import com.iseonjwa.payment.support.PgClientsTestConfig;
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
import com.iseonjwa.payment.pg.application.PgClient;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// 빌링키 등록 횟수 제한(ISE-241). 분 상한을 2로 낮춰 상한 번째까지와 그 다음 요청을 HTTP로 확인한다
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.config.import=", "spring.cloud.config.enabled=false", "eureka.client.enabled=false",
                "payment.billing-key.registration-limit.per-minute=2"})
@ActiveProfiles("test")
@Import(PgClientsTestConfig.class)
class BillingKeyRegistrationLimitApiTest {
    private static final long FIRST_USER = 9_250_000;
    private static final long LAST_USER = 9_259_999;
    private static final AtomicLong USERS = new AtomicLong(FIRST_USER);

    private final HttpClient http = HttpClient.newHttpClient();

    @LocalServerPort
    int port;
    @Autowired
    JdbcTemplate jdbc;
    @MockitoBean
    PgClient pg;

    @BeforeEach
    void stubPg() {
        reset(pg);
        when(pg.issueBillingKey(anyString(), anyString()))
                .thenAnswer(invocation -> new PgClient.BillingKeyIssued("bk-plain", "4242"));
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM billing_keys WHERE user_id BETWEEN ? AND ?", FIRST_USER, LAST_USER);
    }

    @Test
    void 상한_번째까지는_201이고_다음_요청은_PG를_부르지_않고_429다() throws Exception {
        long user = USERS.incrementAndGet();
        assertEquals(201, register(user).statusCode());
        assertEquals(201, register(user).statusCode());

        var limited = register(user);

        assertEquals(429, limited.statusCode());
        assertEquals("BILLING_KEY_RATE_LIMITED", JsonMapper.builder().build().readTree(limited.body()).get("code").asString());
        var retryAfter = Long.parseLong(limited.headers().firstValue("Retry-After").orElseThrow());
        assertTrue(retryAfter >= 1 && retryAfter <= 60, "Retry-After=" + retryAfter);
        verify(pg, times(2)).issueBillingKey(anyString(), anyString());
    }

    @Test
    void 다른_사용자는_영향이_없다() throws Exception {
        long limitedUser = USERS.incrementAndGet();
        long other = USERS.incrementAndGet();
        register(limitedUser);
        register(limitedUser);
        assertEquals(429, register(limitedUser).statusCode());

        assertEquals(201, register(other).statusCode());
    }

    @Test
    void 해지는_제한이_없다() throws Exception {
        long user = USERS.incrementAndGet();
        register(user);
        register(user);
        assertEquals(429, register(user).statusCode());

        for (var i = 0; i < 5; i++) {
            assertEquals(200, revoke(user).statusCode());
        }
    }

    private HttpResponse<String> register(long userId) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/payments/billing-keys"))
                .header("Content-Type", "application/json")
                .header("X-User-Id", Long.toString(userId)).header("X-User-Role", "USER")
                .POST(HttpRequest.BodyPublishers.ofString("{\"authKey\":\"auth\"}"));
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> revoke(long userId) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/payments/billing-keys/me"))
                .header("X-User-Id", Long.toString(userId)).header("X-User-Role", "USER").DELETE();
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
