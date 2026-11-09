package com.iseonjwa.mockpg.payment;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 지연 중에 {@code POST /mock-pg/reset}이 와도 새 원장이 옛 요청에 오염되지 않는다(mock-pg.md 2절 I1, 10절 테스트 목록).
 * 요청 하나는 처음 얻은 원장만 끝까지 쓰므로, 지연이 끝난 옛 요청은 옛 원장에만 쓰고 호출 계수도 옛 원장에 남는다.
 * 원장 수준의 같은 검증은 {@code LedgerHolderTest}가 맡고, 여기서는 HTTP 요청 경로로 확인한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ResetDuringDelayApiTest {
    private static final long DELAY_MILLIS = 600;

    @LocalServerPort
    int port;

    private final HttpClient http = HttpClient.newHttpClient();
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final String orderId = UUID.randomUUID().toString();

    @BeforeEach
    @AfterEach
    void reset() throws Exception {
        assertEquals(204, post("/mock-pg/reset", null).statusCode());
    }

    @Test
    void 승인이_지연되는_동안_초기화해도_새_원장에는_그_주문이_남지_않는다() throws Exception {
        fault("{\"target\":\"APPROVE\",\"mode\":\"DELAY\",\"delayMs\":" + DELAY_MILLIS + ",\"count\":1}");
        var approval = CompletableFuture.supplyAsync(() -> unchecked(() -> approve()));
        awaitStatus("IN_FLIGHT");

        assertEquals(204, post("/mock-pg/reset", null).statusCode());
        var oldRequest = approval.get(5, TimeUnit.SECONDS);

        // 지연이 끝난 옛 요청은 정상 응답을 받지만 새 원장에는 아무것도 쓰지 않았다
        assertEquals(200, oldRequest.statusCode(), oldRequest.body());
        assertEquals(404, get("/mock-pg/ledger/payments/" + orderId).statusCode());
        var summary = json(get("/mock-pg/ledger/summary"));
        assertEquals(0, summary.get("orderCount").asLong());
        assertEquals(0, summary.get("approvedCount").asLong());
        assertEquals(0, summary.get("inFlightCount").asLong());
        assertEquals(404, get("/mock-pg/payments/" + orderId).statusCode());
    }

    @Test
    void 환불이_지연되는_동안_초기화해도_새_원장에는_환불과_호출_계수가_남지_않는다() throws Exception {
        var pgTxId = json(approve()).get("pgTxId").asString();
        var refundId = UUID.randomUUID().toString();
        fault("{\"target\":\"REFUND\",\"mode\":\"DELAY\",\"delayMs\":" + DELAY_MILLIS + ",\"count\":1}");
        var refund = CompletableFuture.supplyAsync(() -> unchecked(() -> post("/mock-pg/refunds",
                "{\"pgTxId\":\"" + pgTxId + "\",\"refundId\":\"" + refundId + "\",\"amount\":20000,\"currency\":\"KRW\"}")));
        awaitFaultClaimed("REFUND");

        assertEquals(204, post("/mock-pg/reset", null).statusCode());
        var oldRequest = refund.get(5, TimeUnit.SECONDS);

        assertEquals(200, oldRequest.statusCode(), oldRequest.body());
        var refunds = json(get("/mock-pg/ledger/refunds?pgTxId=" + pgTxId));
        assertEquals(0, refunds.get("refundCount").asInt());
        assertEquals(0, refunds.get("refunds").size());
        assertEquals(0, json(get("/mock-pg/ledger/summary")).get("refundCount").asLong());
        assertEquals(404, get("/mock-pg/refunds/" + refundId).statusCode());
    }

    // 지연 중인 승인이 원장에 올라올 때까지 기다린다. 그래야 초기화가 지연 중에 도착한다
    private void awaitStatus(String expected) throws Exception {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            var ledger = get("/mock-pg/ledger/payments/" + orderId);
            if (ledger.statusCode() == 200 && expected.equals(json(ledger).get("status").asString())) return;
            Thread.sleep(10);
        }
        throw new AssertionError("주문이 " + expected + " 상태가 되지 않았다");
    }

    // 횟수 1짜리 규칙이 소비되면 요청이 서버에 도착해 지연에 들어간 것이다
    private void awaitFaultClaimed(String target) throws Exception {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            for (var rule : json(get("/mock-pg/faults"))) {
                if (target.equals(rule.get("target").asString()) && "NONE".equals(rule.get("mode").asString())) return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("요청이 장애 설정을 소비하지 않았다 target=" + target);
    }

    private HttpResponse<String> approve() throws Exception {
        var response = post("/mock-pg/payments",
                "{\"orderId\":\"" + orderId + "\",\"amount\":50000,\"currency\":\"KRW\"}");
        assertTrue(response.statusCode() == 200, response.body());
        return response;
    }

    private JsonNode json(HttpResponse<String> response) {
        return mapper.readTree(response.body());
    }

    private void fault(String json) throws Exception {
        var response = post("/mock-pg/faults", json);
        assertEquals(200, response.statusCode(), response.body());
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

    private static <T> T unchecked(ThrowingSupplier<T> supplier) {
        try {
            return supplier.get();
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }
}
