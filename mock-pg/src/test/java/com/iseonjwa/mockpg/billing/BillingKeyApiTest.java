package com.iseonjwa.mockpg.billing;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 발급한 빌링키로 자동결제가 승인되고, 발급하지 않은 빌링키는 거절되는 것을 HTTP로 확인한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class BillingKeyApiTest {
    private static final Pattern BILLING_KEY = Pattern.compile("\"billingKey\":\"([^\"]+)\"");

    @LocalServerPort
    int port;

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void 발급한_빌링키로_자동결제를_승인한다() throws Exception {
        var issued = post("/mock-pg/billing-keys", "{\"authKey\":\"auth-1\",\"customerKey\":\"customer-1\"}");
        assertEquals(200, issued.statusCode(), issued.body());
        var matcher = BILLING_KEY.matcher(issued.body());
        assertTrue(matcher.find(), issued.body());

        var approved = approve(matcher.group(1));

        assertEquals(200, approved.statusCode(), approved.body());
        assertTrue(approved.body().contains("\"status\":\"APPROVED\""), approved.body());
    }

    @Test
    void 발급하지_않은_빌링키는_BILLING_KEY_INVALID로_거절한다() throws Exception {
        var declined = approve("bk-never-issued");

        assertTrue(declined.body().contains("BILLING_KEY_INVALID"), declined.statusCode() + " " + declined.body());
    }

    private HttpResponse<String> approve(String billingKey) throws Exception {
        return post("/mock-pg/payments", "{\"orderId\":\"" + UUID.randomUUID()
                + "\",\"amount\":50000,\"currency\":\"KRW\",\"billingKey\":\"" + billingKey + "\"}");
    }

    private HttpResponse<String> post(String path, String json) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json)).build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
