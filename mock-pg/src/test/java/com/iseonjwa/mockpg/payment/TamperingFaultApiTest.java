package com.iseonjwa.mockpg.payment;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 변조형 장애 모드(mock-pg.md 5절: BAD_REQUEST, MISSING_AMOUNT, WRONG_AMOUNT, FRACTIONAL_AMOUNT)를 HTTP로 확인한다.
 * 변조형은 test 프로필에서만 설정할 수 있다. 프로필별 거절은 {@code FaultTamperingProfileTest}가 맡는다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class TamperingFaultApiTest {
    private static final String INVALID_REQUEST = "{\"code\":\"INVALID_REQUEST\"}";

    @LocalServerPort
    int port;

    private final HttpClient http = HttpClient.newHttpClient();
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final String orderId = UUID.randomUUID().toString();

    // 테스트가 장애를 소비하기 전에 실패해도 남은 설정이 다음 테스트를 바꾸지 않게 한다
    @BeforeEach
    @AfterEach
    void reset() throws Exception {
        assertEquals(204, post("/mock-pg/reset", null).statusCode());
    }

    @Test
    void BAD_REQUEST를_걸면_승인은_400이고_원장은_그대로라_조회는_404다() throws Exception {
        fault("APPROVE", "BAD_REQUEST");

        var response = approve(orderId);

        assertEquals(400, response.statusCode(), response.body());
        assertEquals(INVALID_REQUEST, response.body());
        assertEquals(404, find(orderId).statusCode());
        assertEquals(200, approve(orderId).statusCode());
    }

    @Test
    void MISSING_AMOUNT를_건_승인은_응답에서_금액과_통화만_빠지고_저장은_정상이다() throws Exception {
        fault("APPROVE", "MISSING_AMOUNT");

        var response = approve(orderId);

        assertEquals(200, response.statusCode(), response.body());
        var body = mapper.readTree(response.body());
        assertEquals("APPROVED", body.get("status").asString());
        assertTrue(body.has("pgTxId") && body.has("approvedAt"), response.body());
        assertFalse(body.has("amount") || body.has("currency"), response.body());
        var stored = mapper.readTree(find(orderId).body());
        assertEquals(0, new java.math.BigDecimal("50000").compareTo(stored.get("amount").decimalValue()));
        assertEquals("KRW", stored.get("currency").asString());
    }

    @Test
    void MISSING_AMOUNT를_건_조회는_금액과_통화가_빠지고_다음_조회는_정상이다() throws Exception {
        approve(orderId);
        fault("PAYMENT_LOOKUP", "MISSING_AMOUNT");

        var tampered = mapper.readTree(find(orderId).body());
        var normal = mapper.readTree(find(orderId).body());

        assertFalse(tampered.has("amount") || tampered.has("currency"), tampered.toString());
        assertEquals("APPROVED", tampered.get("status").asString());
        assertTrue(normal.has("amount") && normal.has("currency"), normal.toString());
    }

    @Test
    void MISSING_AMOUNT를_건_환불과_환불_조회는_금액과_통화가_빠지고_저장은_정상이다() throws Exception {
        var pgTxId = mapper.readTree(approve(orderId).body()).get("pgTxId").asString();
        var refundId = UUID.randomUUID().toString();
        fault("REFUND", "MISSING_AMOUNT");

        var refund = post("/mock-pg/refunds", "{\"pgTxId\":\"" + pgTxId + "\",\"refundId\":\"" + refundId
                + "\",\"amount\":20000,\"currency\":\"KRW\"}");

        assertEquals(200, refund.statusCode(), refund.body());
        var refundBody = mapper.readTree(refund.body());
        assertEquals("REFUNDED", refundBody.get("status").asString());
        assertFalse(refundBody.has("amount") || refundBody.has("currency"), refund.body());

        fault("REFUND_LOOKUP", "MISSING_AMOUNT");
        var tampered = mapper.readTree(get("/mock-pg/refunds/" + refundId).body());
        var normal = mapper.readTree(get("/mock-pg/refunds/" + refundId).body());
        assertFalse(tampered.has("amount") || tampered.has("currency"), tampered.toString());
        assertEquals(0, new java.math.BigDecimal("20000").compareTo(normal.get("amount").decimalValue()));
        assertEquals("KRW", normal.get("currency").asString());
    }

    @Test
    void WRONG_AMOUNT를_건_승인은_요청에서_1000원을_뺀_금액으로_저장하고_승인한다() throws Exception {
        fault("APPROVE", "WRONG_AMOUNT");

        var response = approve(orderId);

        assertEquals(200, response.statusCode(), response.body());
        assertAmount("49000", response.body());
        assertAmount("49000", find(orderId).body());
        // 장애를 푼 뒤 같은 주문을 다시 승인해도 저장된 금액을 돌려준다(멱등)
        assertAmount("49000", approve(orderId).body());
    }

    @Test
    void FRACTIONAL_AMOUNT를_건_승인은_요청에서_0점5원을_뺀_금액으로_저장하고_승인한다() throws Exception {
        fault("APPROVE", "FRACTIONAL_AMOUNT");

        var response = approve(orderId);

        assertEquals(200, response.statusCode(), response.body());
        assertAmount("49999.5", response.body());
        assertAmount("49999.5", find(orderId).body());
    }

    @Test
    void 이미_결과가_있는_주문에는_금액_변조가_적용되지_않고_횟수는_쓴다() throws Exception {
        var first = approve(orderId);
        fault("APPROVE", "WRONG_AMOUNT");

        assertEquals(first.body(), approve(orderId).body());
        assertAmount("50000", approve(UUID.randomUUID().toString()).body());
    }

    @ParameterizedTest
    @CsvSource({
            "PAYMENT_LOOKUP, BAD_REQUEST", "CANCEL, BAD_REQUEST", "REFUND, BAD_REQUEST", "REFUND_LOOKUP, BAD_REQUEST",
            "CANCEL, MISSING_AMOUNT",
            "PAYMENT_LOOKUP, WRONG_AMOUNT", "CANCEL, WRONG_AMOUNT", "REFUND, WRONG_AMOUNT", "REFUND_LOOKUP, WRONG_AMOUNT",
            "PAYMENT_LOOKUP, FRACTIONAL_AMOUNT", "CANCEL, FRACTIONAL_AMOUNT", "REFUND, FRACTIONAL_AMOUNT",
            "REFUND_LOOKUP, FRACTIONAL_AMOUNT"
    })
    void 걸_수_없는_변조형_조합은_설정할_때_400이다(String target, String mode) throws Exception {
        var response = post("/mock-pg/faults", "{\"target\":\"" + target + "\",\"mode\":\"" + mode + "\"}");

        assertEquals(400, response.statusCode(), response.body());
        assertEquals(INVALID_REQUEST, response.body());
    }

    @Test
    void 횟수_1인_변조를_동시에_소비해도_정확히_한_요청에만_적용된다() throws Exception {
        fault("APPROVE", "WRONG_AMOUNT");
        var threads = 20;
        var start = new CountDownLatch(1);
        var responses = new ArrayList<CompletableFuture<JsonNode>>();
        for (int i = 0; i < threads; i++) {
            var order = UUID.randomUUID().toString();
            responses.add(CompletableFuture.supplyAsync(() -> {
                try {
                    start.await();
                    return mapper.readTree(approve(order).body());
                } catch (Exception exception) {
                    throw new IllegalStateException(exception);
                }
            }));
        }
        start.countDown();

        long tampered = 0;
        for (var future : responses) {
            if (future.get().get("amount").decimalValue().compareTo(new java.math.BigDecimal("50000")) != 0) tampered++;
        }

        assertEquals(1, tampered);
    }

    private static void assertAmount(String expected, String body) {
        var amount = JsonMapper.builder().build().readTree(body).get("amount").decimalValue();
        assertEquals(0, new java.math.BigDecimal(expected).compareTo(amount), body);
    }

    private void fault(String target, String mode) throws Exception {
        var response = post("/mock-pg/faults", "{\"target\":\"" + target + "\",\"mode\":\"" + mode + "\",\"count\":1}");
        assertEquals(200, response.statusCode(), response.body());
    }

    private HttpResponse<String> approve(String id) throws Exception {
        return post("/mock-pg/payments", "{\"orderId\":\"" + id + "\",\"amount\":50000,\"currency\":\"KRW\"}");
    }

    private HttpResponse<String> find(String id) throws Exception {
        return get("/mock-pg/payments/" + id);
    }

    private HttpResponse<String> post(String path, String json) throws Exception {
        var body = json == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json);
        return http.send(HttpRequest.newBuilder(uri(path)).header("Content-Type", "application/json").POST(body).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(uri(path)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }
}
