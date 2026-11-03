package com.iseonjwa.mockpg.ledger;

import com.iseonjwa.mockpg.fault.FaultInjector;
import com.iseonjwa.mockpg.fault.FaultMode;
import com.iseonjwa.mockpg.fault.FaultRules;
import com.iseonjwa.mockpg.fault.FaultTarget;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 원장 조회 API 5개의 응답(mock-pg.md 9절)을 HTTP로 확인한다. 요약은 원장 전체를 세므로 테스트마다 새 원장에서 시작한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class LedgerApiTest {
    @LocalServerPort
    int port;

    @Autowired
    LedgerHolder ledgers;

    @Autowired
    FaultInjector faults;

    private final HttpClient http = HttpClient.newHttpClient();
    private final JsonMapper mapper = JsonMapper.builder().build();

    @BeforeEach
    void resetAll() throws Exception {
        assertEquals(204, post("/mock-pg/reset", null, null).statusCode());
    }

    @Test
    void 주문_원장은_상태와_호출_계수와_멱등_키를_돌려준다() throws Exception {
        var orderId = UUID.randomUUID().toString();
        var approved = json(post("/mock-pg/payments", approveBody(orderId, 50_000), "key-1"));
        post("/mock-pg/payments", approveBody(orderId, 50_000), "key-2");
        get("/mock-pg/payments/" + orderId);
        var pgTxId = approved.get("pgTxId").asString();
        refund(pgTxId, UUID.randomUUID().toString(), 20_000);

        var response = get("/mock-pg/ledger/payments/" + orderId);

        assertEquals(200, response.statusCode(), response.body());
        var body = json(response);
        assertEquals(orderId, body.get("orderId").asString());
        assertEquals("APPROVED", body.get("status").asString());
        assertEquals(pgTxId, body.get("pgTxId").asString());
        assertEquals(50_000, body.get("amount").asLong());
        assertEquals("KRW", body.get("currency").asString());
        assertEquals(2, body.get("approveCalls").asLong());
        assertEquals(1, body.get("lookupCalls").asLong());
        assertEquals(0, body.get("cancelCalls").asLong());
        assertEquals(20_000, body.get("refundedTotal").asLong());
        assertEquals("key-2", body.get("idempotencyKeys").get("last").asString());
        assertEquals(2, body.get("idempotencyKeys").get("distinctCount").asLong());
    }

    @Test
    void 호출도_상태도_없는_주문의_원장은_404다() throws Exception {
        var response = get("/mock-pg/ledger/payments/" + UUID.randomUUID());

        assertEquals(404, response.statusCode());
        assertEquals("{\"code\":\"NOT_FOUND\"}", response.body());
    }

    @Test
    void 원장_조회는_호출_계수를_올리지_않는다() throws Exception {
        var orderId = UUID.randomUUID().toString();
        post("/mock-pg/payments", approveBody(orderId, 50_000), null);

        get("/mock-pg/ledger/payments/" + orderId);
        get("/mock-pg/ledger/summary");
        get("/mock-pg/ledger/orders");

        var body = json(get("/mock-pg/ledger/payments/" + orderId));
        assertEquals(1, body.get("approveCalls").asLong());
        assertEquals(0, body.get("lookupCalls").asLong());
    }

    @Test
    void 환불_원장은_결제의_환불_목록과_건수와_누계를_돌려준다() throws Exception {
        var pgTxId = approvedPayment();
        var refundId = UUID.randomUUID().toString();
        refund(pgTxId, refundId, 20_000);
        refund(pgTxId, refundId, 20_000);
        refund(pgTxId, UUID.randomUUID().toString(), 10_000);
        // 거절은 저장하지 않으므로 목록과 누계에 들어가지 않는다
        assertEquals(422, refund(pgTxId, UUID.randomUUID().toString(), 40_000).statusCode());

        var response = get("/mock-pg/ledger/refunds?pgTxId=" + pgTxId);

        assertEquals(200, response.statusCode(), response.body());
        var body = json(response);
        assertEquals(2, body.get("refundCount").asLong());
        assertEquals(30_000, body.get("refundedTotal").asLong());
        var first = body.get("refunds").get(0);
        assertEquals(refundId, first.get("refundId").asString());
        assertTrue(first.get("refundTxId").asString().startsWith("rf-"));
        assertEquals(20_000, first.get("amount").asLong());
        assertEquals("KRW", first.get("currency").asString());
        assertEquals(2, first.get("refundCalls").asLong());
        assertEquals(1, body.get("refunds").get(1).get("refundCalls").asLong());
    }

    @Test
    void 환불_원장은_pgTxId가_없으면_400이고_모르는_결제는_빈_목록이다() throws Exception {
        assertEquals(400, get("/mock-pg/ledger/refunds").statusCode());

        var body = json(get("/mock-pg/ledger/refunds?pgTxId=pg-unknown"));
        assertEquals(0, body.get("refundCount").asLong());
        assertEquals(0, body.get("refunds").size());
    }

    @Test
    void 요약은_상태별_건수와_합계와_처리_중_건수를_돌려준다() throws Exception {
        var pgTxId = approvedPayment();
        approvedPayment();
        post("/mock-pg/payments/" + UUID.randomUUID() + "/cancel", null, null);
        faults.apply(FaultRules.of(FaultTarget.APPROVE, FaultMode.DECLINE, 1));
        post("/mock-pg/payments", approveBody(UUID.randomUUID().toString(), 50_000), null);
        refund(pgTxId, UUID.randomUUID().toString(), 15_000);

        var body = json(get("/mock-pg/ledger/summary"));

        assertEquals(2, body.get("approvedCount").asLong());
        assertEquals(1, body.get("declinedCount").asLong());
        assertEquals(1, body.get("canceledCount").asLong());
        assertEquals(1, body.get("refundCount").asLong());
        assertEquals(100_000, body.get("approvedTotal").asLong());
        assertEquals(15_000, body.get("refundedTotal").asLong());
        assertEquals(0, body.get("inFlightCount").asLong());
        assertEquals(4, body.get("orderCount").asLong());
    }

    @Test
    void 주문_목록은_상태로_거르고_pgTxId를_돌려준다() throws Exception {
        var pgTxId = approvedPayment();
        post("/mock-pg/payments/" + UUID.randomUUID() + "/cancel", null, null);

        var body = json(get("/mock-pg/ledger/orders?status=APPROVED"));

        assertEquals(1, body.get("orders").size());
        assertEquals(pgTxId, body.get("orders").get(0).get("pgTxId").asString());
        assertEquals("APPROVED", body.get("orders").get(0).get("status").asString());
        assertTrue(body.get("nextCursor").isNull());
    }

    @Test
    void 주문_목록은_만들어진_순서로_cursor를_따라_빠짐없이_중복_없이_넘어간다() throws Exception {
        var created = new ArrayList<String>();
        for (int i = 0; i < 7; i++) {
            var orderId = "order-" + i + "-" + UUID.randomUUID();
            created.add(orderId);
            post("/mock-pg/payments", approveBody(orderId, 50_000), null);
        }

        var seen = new ArrayList<String>();
        String cursor = "0";
        int pages = 0;
        while (cursor != null) {
            var body = json(get("/mock-pg/ledger/orders?status=APPROVED&limit=3&cursor=" + cursor));
            for (var order : body.get("orders")) seen.add(order.get("orderId").asString());
            cursor = body.get("nextCursor").isNull() ? null : body.get("nextCursor").asString();
            pages++;
        }

        assertEquals(created, seen);
        assertEquals(3, pages);
    }

    @Test
    void 주문_목록의_잘못된_매개변수는_400이다() throws Exception {
        for (var query : List.of("status=UNKNOWN", "limit=0", "limit=1001", "limit=abc", "cursor=-1", "cursor=abc")) {
            var response = get("/mock-pg/ledger/orders?" + query);
            assertEquals(400, response.statusCode(), query);
            assertEquals("{\"code\":\"INVALID_REQUEST\"}", response.body(), query);
        }
    }

    @Test
    void 초기화하면_원장과_장애_설정이_모두_지워진다() throws Exception {
        var orderId = UUID.randomUUID().toString();
        post("/mock-pg/payments", approveBody(orderId, 50_000), null);
        faults.apply(FaultRules.of(FaultTarget.APPROVE, FaultMode.SERVER_ERROR, null));

        assertEquals(204, post("/mock-pg/reset", null, null).statusCode());

        assertEquals(404, get("/mock-pg/ledger/payments/" + orderId).statusCode());
        assertEquals(0, json(get("/mock-pg/ledger/summary")).get("orderCount").asLong());
        faults.currentRules().forEach(rule -> assertEquals(FaultMode.NONE, rule.mode(), rule.toString()));
        // 장애 설정이 남아 있었다면 500이다
        assertEquals(200, post("/mock-pg/payments", approveBody(orderId, 50_000), null).statusCode());
    }

    private String approvedPayment() throws Exception {
        var response = post("/mock-pg/payments", approveBody(UUID.randomUUID().toString(), 50_000), null);
        assertEquals(200, response.statusCode(), response.body());
        return json(response).get("pgTxId").asString();
    }

    private HttpResponse<String> refund(String pgTxId, String refundId, long amount) throws Exception {
        return post("/mock-pg/refunds", "{\"pgTxId\":\"" + pgTxId + "\",\"refundId\":\"" + refundId
                + "\",\"amount\":" + amount + ",\"currency\":\"KRW\"}", null);
    }

    private static String approveBody(String orderId, long amount) {
        return "{\"orderId\":\"" + orderId + "\",\"amount\":" + amount + ",\"currency\":\"KRW\"}";
    }

    private JsonNode json(HttpResponse<String> response) {
        return mapper.readTree(response.body());
    }

    private HttpResponse<String> post(String path, String json, String idempotencyKey) throws Exception {
        var builder = HttpRequest.newBuilder(uri(path)).header("Content-Type", "application/json");
        if (idempotencyKey != null) builder.header("Idempotency-Key", idempotencyKey);
        var body = json == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json);
        return http.send(builder.POST(body).build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(uri(path)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }
}
