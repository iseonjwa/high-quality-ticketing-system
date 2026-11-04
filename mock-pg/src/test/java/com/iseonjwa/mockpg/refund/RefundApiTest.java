package com.iseonjwa.mockpg.refund;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 환불·환불 조회 API의 응답(pg-integration.md 6절, mock-pg.md 8·11절)을 HTTP로 확인한다. 승인 금액은 50,000원이다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class RefundApiTest {
    private static final String NOT_FOUND = "{\"code\":\"NOT_FOUND\"}";
    private static final String NOT_REFUNDABLE = "{\"status\":\"REJECTED\",\"code\":\"NOT_REFUNDABLE\"}";
    private static final String OTHER = "{\"status\":\"REJECTED\",\"code\":\"OTHER\"}";
    private static final String INVALID_REQUEST = "{\"code\":\"INVALID_REQUEST\"}";

    @LocalServerPort
    int port;

    private final HttpClient http = HttpClient.newHttpClient();
    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    void 환불하면_환불_거래_식별자와_금액_통화_환불_시각을_돌려준다() throws Exception {
        var pgTxId = approvedPayment();

        var response = refund(pgTxId, newRefundId(), 20_000);

        assertEquals(200, response.statusCode(), response.body());
        var body = mapper.readTree(response.body());
        assertTrue(body.get("refundTxId").asString().startsWith("rf-"), response.body());
        assertEquals(pgTxId, body.get("pgTxId").asString());
        assertEquals("REFUNDED", body.get("status").asString());
        assertTrue(body.get("amount").isNumber(), response.body());
        assertEquals(20_000, body.get("amount").asLong());
        assertEquals("KRW", body.get("currency").asString());
        // 어댑터는 환불 시각을 ISO-8601 문자열로 읽는다(Instant.parse)
        assertTrue(response.body().matches(".*\"refundedAt\":\"\\d{4}-\\d{2}-\\d{2}T[^\"]+Z\".*"), response.body());
    }

    @Test
    void 같은_환불_식별자의_재요청과_조회는_같은_본문이다() throws Exception {
        var pgTxId = approvedPayment();
        var refundId = newRefundId();
        var first = refund(pgTxId, refundId, 20_000);

        assertEquals(first.body(), refund(pgTxId, refundId, 20_000).body());
        var found = get("/mock-pg/refunds/" + refundId);
        assertEquals(200, found.statusCode());
        assertEquals(first.body(), found.body());
    }

    @Test
    void 모르는_환불_식별자의_조회는_본문_코드가_있는_404다() throws Exception {
        assertResponse(404, NOT_FOUND, get("/mock-pg/refunds/" + newRefundId()));
    }

    @Test
    void 거절된_환불_식별자의_조회도_404다() throws Exception {
        var refundId = newRefundId();

        assertResponse(422, NOT_REFUNDABLE, refund("pg-없는-결제", refundId, 10_000));

        assertResponse(404, NOT_FOUND, get("/mock-pg/refunds/" + refundId));
    }

    @Test
    void 승인된_결제가_없으면_NOT_REFUNDABLE이다() throws Exception {
        assertResponse(422, NOT_REFUNDABLE, refund("pg-" + UUID.randomUUID(), newRefundId(), 10_000));
    }

    @Test
    void 통화가_다르면_NOT_REFUNDABLE이다() throws Exception {
        var pgTxId = approvedPayment();

        assertResponse(422, NOT_REFUNDABLE, post("/mock-pg/refunds", refundBody(pgTxId, newRefundId(), 10_000, "USD")));
    }

    @Test
    void 환불_합계가_승인_금액을_넘으면_NOT_REFUNDABLE이다() throws Exception {
        var pgTxId = approvedPayment();

        assertEquals(200, refund(pgTxId, newRefundId(), 50_000).statusCode());
        assertResponse(422, NOT_REFUNDABLE, refund(pgTxId, newRefundId(), 1));
    }

    @Test
    void 같은_환불_식별자를_다른_결제로_보내면_OTHER다() throws Exception {
        var first = approvedPayment();
        var other = approvedPayment();
        var refundId = newRefundId();
        assertEquals(200, refund(first, refundId, 10_000).statusCode());

        assertResponse(422, OTHER, refund(other, refundId, 10_000));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"pgTxId\":\"pg-1\",\"refundId\":\"r-1\",\"amount\":0,\"currency\":\"KRW\"}",
            "{\"pgTxId\":\"pg-1\",\"amount\":1000,\"currency\":\"KRW\"}",
            "{\"pgTxId\":\"pg-1\",\"refundId\":\"r-1\",\"amount\":1000,\"currency\":\"KRW\",\"memo\":\"x\"}",
            "{\"pgTxId\":\" \",\"refundId\":\"r-1\",\"amount\":1000,\"currency\":\"KRW\"}",
            "{not json"
    })
    void 형식이_틀린_환불_요청은_400_INVALID_REQUEST다(String body) throws Exception {
        assertResponse(400, INVALID_REQUEST, post("/mock-pg/refunds", body));
    }

    @Test
    void 환불_사유는_선택이다() throws Exception {
        var pgTxId = approvedPayment();
        var withReason = "{\"pgTxId\":\"" + pgTxId + "\",\"refundId\":\"" + newRefundId()
                + "\",\"amount\":1000,\"currency\":\"KRW\",\"reason\":\"EXPIRED_AFTER_APPROVAL\"}";

        assertEquals(200, post("/mock-pg/refunds", withReason).statusCode());
    }

    @Test
    void 같은_환불_식별자로_동시에_보내도_모두_같은_본문이고_남은_금액만_더_환불된다() throws Exception {
        var pgTxId = approvedPayment();
        var refundId = newRefundId();
        var start = new CountDownLatch(1);
        var requests = new ArrayList<Future<HttpResponse<String>>>();
        try (var executor = Executors.newFixedThreadPool(16)) {
            for (int i = 0; i < 16; i++) {
                requests.add(executor.submit(() -> {
                    start.await();
                    return refund(pgTxId, refundId, 20_000);
                }));
            }
            start.countDown();

            var bodies = new HashSet<String>();
            for (var request : requests) {
                var response = request.get();
                assertEquals(200, response.statusCode(), response.body());
                bodies.add(response.body());
            }
            assertEquals(1, bodies.size());
        }

        assertEquals(200, refund(pgTxId, newRefundId(), 30_000).statusCode());
        assertResponse(422, NOT_REFUNDABLE, refund(pgTxId, newRefundId(), 1));
    }

    // 승인해서 PG 거래 식별자를 얻는다
    private String approvedPayment() throws Exception {
        var body = "{\"orderId\":\"" + UUID.randomUUID() + "\",\"amount\":50000,\"currency\":\"KRW\"}";
        var response = post("/mock-pg/payments", body);
        assertEquals(200, response.statusCode(), response.body());
        JsonNode node = mapper.readTree(response.body());
        return node.get("pgTxId").asString();
    }

    private static String newRefundId() {
        return UUID.randomUUID().toString();
    }

    private HttpResponse<String> refund(String pgTxId, String refundId, long amount) throws Exception {
        return post("/mock-pg/refunds", refundBody(pgTxId, refundId, amount, "KRW"));
    }

    private static String refundBody(String pgTxId, String refundId, long amount, String currency) {
        return "{\"pgTxId\":\"" + pgTxId + "\",\"refundId\":\"" + refundId + "\",\"amount\":" + amount
                + ",\"currency\":\"" + currency + "\"}";
    }

    private HttpResponse<String> post(String path, String json) throws Exception {
        return send(HttpRequest.newBuilder(uri(path)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json)).build());
    }

    private HttpResponse<String> get(String path) throws Exception {
        return send(HttpRequest.newBuilder(uri(path)).GET().build());
    }

    private HttpResponse<String> send(HttpRequest request) throws Exception {
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }

    private static void assertResponse(int status, String body, HttpResponse<String> response) {
        assertEquals(status, response.statusCode(), response.body());
        assertEquals(body, response.body());
    }
}
