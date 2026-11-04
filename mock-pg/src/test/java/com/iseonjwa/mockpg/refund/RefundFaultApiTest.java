package com.iseonjwa.mockpg.refund;

import com.iseonjwa.mockpg.ledger.LedgerHolder;
import com.iseonjwa.mockpg.ledger.LedgerViews.PaymentRefundsView;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 환불·환불 조회 장애 주입(mock-pg.md 5절 모드와 적용 표)을 HTTP로 확인한다. 장애는 설정 API로 건다.
 * 장애가 걸린 요청도 호출 계수에 들어가는지를 함께 본다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class RefundFaultApiTest {
    private static final long DELAY_MILLIS = 400;
    private static final String PROCESSING = "{\"status\":\"PROCESSING\"}";
    private static final String SERVER_ERROR = "{\"code\":\"SERVER_ERROR\"}";
    private static final String NOT_REFUNDABLE = "{\"status\":\"REJECTED\",\"code\":\"NOT_REFUNDABLE\"}";

    @LocalServerPort
    int port;

    @Autowired
    LedgerHolder ledgers;

    private final HttpClient http = HttpClient.newHttpClient();
    private final JsonMapper mapper = JsonMapper.builder().build();
    private String pgTxId;
    private final String refundId = UUID.randomUUID().toString();

    @BeforeEach
    void approve() throws Exception {
        reset();
        var response = post("/mock-pg/payments", "{\"orderId\":\"" + UUID.randomUUID()
                + "\",\"amount\":50000,\"currency\":\"KRW\"}");
        pgTxId = mapper.readTree(response.body()).get("pgTxId").asString();
    }

    // 테스트가 장애를 소비하기 전에 실패해도 남은 설정이 다음 테스트를 바꾸지 않게 한다
    @AfterEach
    void reset() throws Exception {
        assertEquals(204, post("/mock-pg/reset", null).statusCode());
    }

    @Test
    void 환불_DELAY는_저장하기_전에_늦춘다_그_사이_같은_환불이_먼저_저장되면_같은_응답을_받는다() throws Exception {
        fault("{\"target\":\"REFUND\",\"mode\":\"DELAY\",\"delayMs\":" + DELAY_MILLIS + ",\"count\":1}");

        var startedAt = System.nanoTime();
        var slow = CompletableFuture.supplyAsync(() -> sendRefund(20_000));
        awaitFaultClaimed("REFUND");
        // 지연 중인 첫 요청은 아직 저장 전이므로 둘째가 먼저 저장한다
        var fast = sendRefund(20_000);
        var fastElapsed = Duration.ofNanos(System.nanoTime() - startedAt);
        assertEquals(200, fast.statusCode(), fast.body());
        assertTrue(fastElapsed.toMillis() < DELAY_MILLIS, "둘째 요청이 지연을 기다렸다 elapsed=" + fastElapsed);

        var first = slow.get(5, TimeUnit.SECONDS);
        assertEquals(200, first.statusCode(), first.body());
        assertTrue(Duration.ofNanos(System.nanoTime() - startedAt).toMillis() >= DELAY_MILLIS);
        assertEquals(fast.body(), first.body());
        var refunds = refunds();
        assertEquals(1, refunds.refundCount());
        assertEquals(20_000, refunds.refundedTotal().longValue());
        assertEquals(2, refunds.refunds().getFirst().refundCalls());
    }

    @Test
    void 환불_PROCESSING은_저장하지_않고_202이며_호출은_센다() throws Exception {
        fault("{\"target\":\"REFUND\",\"mode\":\"PROCESSING\",\"count\":1}");

        assertResponse(202, PROCESSING, sendRefund(20_000));

        assertEquals(0, refunds().refundCount());
        assertEquals(404, get("/mock-pg/refunds/" + refundId).statusCode());
        // 장애가 걸렸던 첫 호출도 계수에 들어가 있다
        assertEquals(200, sendRefund(20_000).statusCode());
        assertEquals(2, refunds().refunds().getFirst().refundCalls());
    }

    @Test
    void 환불_SERVER_ERROR는_저장하지_않고_500이며_호출은_센다() throws Exception {
        fault("{\"target\":\"REFUND\",\"mode\":\"SERVER_ERROR\",\"count\":1}");

        assertResponse(500, SERVER_ERROR, sendRefund(20_000));

        assertEquals(0, refunds().refundCount());
        assertEquals(200, sendRefund(20_000).statusCode());
        assertEquals(2, refunds().refunds().getFirst().refundCalls());
    }

    @Test
    void 환불_DROP_RESPONSE는_저장한_뒤_응답_없이_연결을_닫는다() throws Exception {
        fault("{\"target\":\"REFUND\",\"mode\":\"DROP_RESPONSE\",\"count\":1}");

        assertThrows(IOException.class, () -> refund(refundId, 20_000));

        var refunds = refunds();
        assertEquals(1, refunds.refundCount());
        assertEquals(20_000, refunds.refundedTotal().longValue());
        assertEquals(1, refunds.refunds().getFirst().refundCalls());
        assertEquals(200, get("/mock-pg/refunds/" + refundId).statusCode());
    }

    @Test
    void 환불_REJECT는_422_NOT_REFUNDABLE이고_저장하지_않는다() throws Exception {
        fault("{\"target\":\"REFUND\",\"mode\":\"REJECT\",\"count\":1}");

        assertResponse(422, NOT_REFUNDABLE, sendRefund(20_000));

        var refunds = refunds();
        assertEquals(0, refunds.refundCount());
        assertEquals(0, refunds.refundedTotal().signum());
        assertEquals(404, get("/mock-pg/refunds/" + refundId).statusCode());
        // 거절을 저장하지 않았으므로 같은 환불 식별자로 다시 보내면 새로 처리된다
        assertEquals(200, sendRefund(20_000).statusCode());
        assertEquals(2, refunds().refunds().getFirst().refundCalls());
    }

    @Test
    void 환불_조회_DELAY는_지연한_뒤_정상_응답한다() throws Exception {
        assertEquals(200, sendRefund(20_000).statusCode());
        fault("{\"target\":\"REFUND_LOOKUP\",\"mode\":\"DELAY\",\"delayMs\":" + DELAY_MILLIS + ",\"count\":1}");

        var startedAt = System.nanoTime();
        var response = get("/mock-pg/refunds/" + refundId);

        assertEquals(200, response.statusCode(), response.body());
        assertTrue(Duration.ofNanos(System.nanoTime() - startedAt).toMillis() >= DELAY_MILLIS);
        assertEquals(1, refunds().refunds().getFirst().refundLookupCalls());
    }

    @Test
    void 환불_조회_PROCESSING은_202이고_SERVER_ERROR는_500이며_호출은_센다() throws Exception {
        assertEquals(200, sendRefund(20_000).statusCode());
        fault("{\"target\":\"REFUND_LOOKUP\",\"mode\":\"PROCESSING\",\"count\":1}");
        assertResponse(202, PROCESSING, get("/mock-pg/refunds/" + refundId));
        fault("{\"target\":\"REFUND_LOOKUP\",\"mode\":\"SERVER_ERROR\",\"count\":1}");
        assertResponse(500, SERVER_ERROR, get("/mock-pg/refunds/" + refundId));

        // 장애가 끝난 뒤 정상 조회
        assertEquals(200, get("/mock-pg/refunds/" + refundId).statusCode());
        assertEquals(3, refunds().refunds().getFirst().refundLookupCalls());
    }

    @ParameterizedTest
    @CsvSource({
            "REFUND_LOOKUP, DROP_RESPONSE", "REFUND_LOOKUP, REJECT", "REFUND, DECLINE", "REFUND_LOOKUP, DECLINE",
            "APPROVE, REJECT", "CANCEL, REJECT", "PAYMENT_LOOKUP, REJECT"
    })
    void 걸_수_없는_조합은_설정할_때_400이다(String target, String mode) throws Exception {
        var response = post("/mock-pg/faults", "{\"target\":\"" + target + "\",\"mode\":\"" + mode + "\"}");

        assertEquals(400, response.statusCode(), response.body());
    }

    @Test
    void 환불_호출에_orderId를_주거나_주문_호출에_refundId를_주면_400이다() throws Exception {
        assertEquals(400, post("/mock-pg/faults",
                "{\"target\":\"REFUND\",\"mode\":\"PROCESSING\",\"orderId\":\"o-1\"}").statusCode());
        assertEquals(400, post("/mock-pg/faults",
                "{\"target\":\"APPROVE\",\"mode\":\"PROCESSING\",\"refundId\":\"r-1\"}").statusCode());
    }

    @Test
    void refundId로_한정하면_다른_환불은_적용받지_않고_횟수도_쓰지_않는다() throws Exception {
        fault("{\"target\":\"REFUND\",\"mode\":\"SERVER_ERROR\",\"count\":1,\"refundId\":\"" + refundId + "\"}");

        // 다른 환불은 정상이고, 한정된 환불의 횟수는 그대로 남아 있다
        assertEquals(200, refund(UUID.randomUUID().toString(), 10_000).statusCode());
        assertResponse(500, SERVER_ERROR, sendRefund(20_000));
        assertEquals(200, sendRefund(20_000).statusCode());
    }

    @Test
    void 환불_조회도_refundId로_한정한다() throws Exception {
        assertEquals(200, sendRefund(20_000).statusCode());
        fault("{\"target\":\"REFUND_LOOKUP\",\"mode\":\"SERVER_ERROR\",\"count\":1,\"refundId\":\"" + refundId + "\"}");

        assertEquals(404, get("/mock-pg/refunds/" + UUID.randomUUID()).statusCode());
        assertResponse(500, SERVER_ERROR, get("/mock-pg/refunds/" + refundId));
        assertEquals(200, get("/mock-pg/refunds/" + refundId).statusCode());
    }

    @Test
    void 초기화하면_환불_장애_설정도_지워진다() throws Exception {
        fault("{\"target\":\"REFUND\",\"mode\":\"SERVER_ERROR\"}");
        fault("{\"target\":\"REFUND_LOOKUP\",\"mode\":\"SERVER_ERROR\"}");

        reset();

        // 원장도 비워지므로 결제를 다시 승인한 뒤 확인한다. 장애 설정이 남아 있었다면 500이다
        approve();
        assertEquals(200, sendRefund(20_000).statusCode());
        assertEquals(200, get("/mock-pg/refunds/" + refundId).statusCode());
    }

    // 지연 중인 요청이 설정을 소비했는지(요청이 서버에 도착했는지)를 설정 API로 확인한다
    private void awaitFaultClaimed(String target) throws Exception {
        var deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            JsonNode rules = mapper.readTree(get("/mock-pg/faults").body());
            for (var rule : rules) {
                if (target.equals(rule.get("target").asString()) && "NONE".equals(rule.get("mode").asString())) return;
            }
            Thread.onSpinWait();
        }
        throw new AssertionError("요청이 장애 설정을 소비하지 않았다 target=" + target);
    }

    private PaymentRefundsView refunds() {
        return ledgers.current().refundsOf(pgTxId);
    }

    private void fault(String json) throws Exception {
        var response = post("/mock-pg/faults", json);
        assertEquals(200, response.statusCode(), response.body());
    }

    private HttpResponse<String> sendRefund(long amount) {
        try {
            return refund(refundId, amount);
        } catch (IOException exception) {
            throw new java.io.UncheckedIOException(exception);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private HttpResponse<String> refund(String id, long amount) throws Exception {
        return post("/mock-pg/refunds", "{\"pgTxId\":\"" + pgTxId + "\",\"refundId\":\"" + id
                + "\",\"amount\":" + amount + ",\"currency\":\"KRW\"}");
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

    private static void assertResponse(int status, String body, HttpResponse<String> response) {
        assertEquals(status, response.statusCode(), response.body());
        assertEquals(body, response.body());
    }
}
