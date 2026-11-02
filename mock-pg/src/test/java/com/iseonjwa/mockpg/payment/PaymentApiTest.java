package com.iseonjwa.mockpg.payment;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class PaymentApiTest {
    @LocalServerPort
    int port;

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void 승인하면_PG_거래_식별자와_금액_통화_승인_시각을_돌려준다() throws Exception {
        var approved = approve(UUID.randomUUID().toString());

        assertEquals(200, approved.statusCode());
        assertTrue(approved.body().contains("\"status\":\"APPROVED\""), approved.body());
        assertTrue(approved.body().contains("\"amount\":50000"), approved.body());
        assertTrue(approved.body().contains("\"currency\":\"KRW\""), approved.body());
        // 어댑터는 승인 시각을 ISO-8601 문자열로 읽는다(Instant.parse)
        assertTrue(approved.body().matches(".*\"approvedAt\":\"\\d{4}-\\d{2}-\\d{2}T[^\"]+Z\".*"), approved.body());
    }

    @Test
    void 같은_주문을_다시_승인하면_처음_저장한_승인을_돌려준다() throws Exception {
        var orderId = UUID.randomUUID().toString();

        var first = approve(orderId);
        var second = approve(orderId);

        assertEquals(200, second.statusCode());
        assertEquals(first.body(), second.body());
    }

    @Test
    void 승인된_주문을_조회하면_승인_응답과_같다() throws Exception {
        var orderId = UUID.randomUUID().toString();
        var approved = approve(orderId);

        var found = get("/mock-pg/payments/" + orderId);

        assertEquals(200, found.statusCode());
        assertEquals(approved.body(), found.body());
    }

    @Test
    void 처음_보는_주문을_조회하면_본문_코드가_있는_404다() throws Exception {
        var found = get("/mock-pg/payments/" + UUID.randomUUID());

        assertEquals(404, found.statusCode());
        assertEquals("{\"code\":\"NOT_FOUND\"}", found.body());
    }

    private HttpResponse<String> approve(String orderId) throws Exception {
        var json = "{\"orderId\":\"" + orderId + "\",\"amount\":50000,\"currency\":\"KRW\"}";
        var request = HttpRequest.newBuilder(uri("/mock-pg/payments")).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json)).build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(uri(path)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }
}
