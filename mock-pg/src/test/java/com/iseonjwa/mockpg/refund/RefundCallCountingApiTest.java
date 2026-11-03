package com.iseonjwa.mockpg.refund;

import com.iseonjwa.mockpg.ledger.LedgerHolder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 환불 호출 계수와 멱등 헤더 기록(mock-pg.md 2절)을 HTTP로 확인한다. 거절(422)도 환불 호출로 센다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class RefundCallCountingApiTest {
    @LocalServerPort
    int port;

    @Autowired
    LedgerHolder ledgers;

    private final HttpClient http = HttpClient.newHttpClient();
    private final JsonMapper mapper = JsonMapper.builder().build();

    @BeforeEach
    void resetLedger() {
        ledgers.reset();
    }

    @Test
    void 같은_환불을_세_번_보내도_저장은_한_번이고_환불_호출은_세_번이다() throws Exception {
        var pgTxId = approvedPayment();
        var refundId = UUID.randomUUID().toString();

        refund(pgTxId, refundId, 20_000, "rk-1");
        refund(pgTxId, refundId, 20_000, "rk-1");
        refund(pgTxId, refundId, 20_000, "rk-1");

        var refunds = ledgers.current().refundsOf(pgTxId);
        assertEquals(1, refunds.refundCount());
        assertEquals(20_000, refunds.refundedTotal().longValue());
        assertEquals(3, refunds.refunds().getFirst().refundCalls());
    }

    @Test
    void 거절된_환불_호출도_센다() throws Exception {
        var pgTxId = approvedPayment();
        var refundId = UUID.randomUUID().toString();
        // 한도를 넘으면 거절돼서 저장되지 않는다
        assertEquals(422, refund(pgTxId, refundId, 60_000, null).statusCode());
        assertEquals(0, ledgers.current().refundsOf(pgTxId).refundCount());

        // 같은 환불 식별자로 조건이 맞게 다시 보내면 저장되고, 앞의 거절 호출도 합쳐서 센다
        assertEquals(200, refund(pgTxId, refundId, 10_000, null).statusCode());

        assertEquals(2, ledgers.current().refundsOf(pgTxId).refunds().getFirst().refundCalls());
    }

    @Test
    void 환불_조회_호출은_환불_식별자별로_센다() throws Exception {
        var pgTxId = approvedPayment();
        var refundId = UUID.randomUUID().toString();
        // 환불 전 조회(404)도 센다
        assertEquals(404, get("/mock-pg/refunds/" + refundId).statusCode());
        refund(pgTxId, refundId, 10_000, null);
        get("/mock-pg/refunds/" + refundId);

        assertEquals(2, ledgers.current().refundsOf(pgTxId).refunds().getFirst().refundLookupCalls());
    }

    @Test
    void 환불_멱등_키는_마지막_값과_서로_다른_값의_수를_기록한다() throws Exception {
        var pgTxId = approvedPayment();
        var refundId = UUID.randomUUID().toString();

        refund(pgTxId, refundId, 10_000, "rk-a");
        refund(pgTxId, refundId, 10_000, "rk-a");
        refund(pgTxId, refundId, 10_000, "rk-b");

        var keys = ledgers.current().refundsOf(pgTxId).refunds().getFirst().idempotencyKeys();
        assertEquals("rk-b", keys.last());
        assertEquals(2, keys.distinctCount());
    }

    @Test
    void 초기화하면_환불_기록과_계수가_사라진다() throws Exception {
        var pgTxId = approvedPayment();
        refund(pgTxId, UUID.randomUUID().toString(), 10_000, null);

        ledgers.reset();

        assertEquals(0, ledgers.current().refundsOf(pgTxId).refundCount());
        assertEquals(0, ledgers.current().summary().refundCount());
    }

    private String approvedPayment() throws Exception {
        var body = "{\"orderId\":\"" + UUID.randomUUID() + "\",\"amount\":50000,\"currency\":\"KRW\"}";
        var response = post("/mock-pg/payments", body, null);
        assertEquals(200, response.statusCode(), response.body());
        return mapper.readTree(response.body()).get("pgTxId").asString();
    }

    private HttpResponse<String> refund(String pgTxId, String refundId, long amount, String idempotencyKey)
            throws Exception {
        return post("/mock-pg/refunds", "{\"pgTxId\":\"" + pgTxId + "\",\"refundId\":\"" + refundId
                + "\",\"amount\":" + amount + ",\"currency\":\"KRW\"}", idempotencyKey);
    }

    private HttpResponse<String> post(String path, String json, String idempotencyKey) throws Exception {
        var builder = HttpRequest.newBuilder(uri(path)).header("Content-Type", "application/json");
        if (idempotencyKey != null) builder.header("Idempotency-Key", idempotencyKey);
        return http.send(builder.POST(HttpRequest.BodyPublishers.ofString(json)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(uri(path)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }
}
