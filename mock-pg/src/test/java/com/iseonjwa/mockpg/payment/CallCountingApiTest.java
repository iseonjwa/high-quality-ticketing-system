package com.iseonjwa.mockpg.payment;

import com.iseonjwa.mockpg.fault.FaultInjector;
import com.iseonjwa.mockpg.fault.FaultMode;
import com.iseonjwa.mockpg.fault.FaultRules;
import com.iseonjwa.mockpg.fault.FaultTarget;
import com.iseonjwa.mockpg.ledger.LedgerHolder;
import com.iseonjwa.mockpg.ledger.LedgerViews.OrderView;
import com.iseonjwa.mockpg.ledger.OrderState.Approved;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 주문 호출 계수와 멱등 헤더 기록(mock-pg.md 2절)을 HTTP로 확인한다. 응답 종류와 상관없이 세는지가 핵심이다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class CallCountingApiTest {
    @LocalServerPort
    int port;

    @Autowired
    LedgerHolder ledgers;

    @Autowired
    FaultInjector faults;

    private final HttpClient http = HttpClient.newHttpClient();
    private final String orderId = UUID.randomUUID().toString();

    // 요약은 원장 전체를 세므로 테스트마다 새 원장에서 시작한다
    @BeforeEach
    void resetLedger() {
        ledgers.reset();
    }

    @AfterEach
    void clearFaults() {
        for (var target : FaultTarget.values()) faults.apply(FaultRules.off(target));
    }

    @Test
    void 같은_주문을_세_번_승인해도_저장은_한_번이고_승인_호출은_세_번이다() throws Exception {
        var first = approve(orderId, null);
        approve(orderId, null);
        approve(orderId, null);

        var view = view();
        assertEquals("APPROVED", view.status());
        assertTrue(first.body().contains(view.pgTxId()), first.body());
        assertEquals(3, view.approveCalls());
        // 같은 주문의 재승인이 새 결제를 만들지 않는다
        assertEquals(1, ledgers.current().summary().approvedCount());
    }

    @Test
    void 망취소된_주문의_승인_409도_승인_호출에_들어간다() throws Exception {
        cancel(orderId);

        assertEquals(409, approve(orderId, null).statusCode());

        assertEquals(1, view().approveCalls());
        assertEquals(1, view().cancelCalls());
    }

    @Test
    void 처리_중_승인의_409도_승인_호출에_들어간다() throws Exception {
        var deciding = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var pending = executor.submit(() -> ledgers.current().approve(orderId, "key-1", () -> {
                deciding.countDown();
                await(release);
                return approved(orderId);
            }));
            assertTrue(deciding.await(5, TimeUnit.SECONDS));
            try {
                assertEquals(409, approve(orderId, "key-2").statusCode());
            } finally {
                release.countDown();
            }
            pending.get(5, TimeUnit.SECONDS);
        }

        assertEquals(2, view().approveCalls());
    }

    @Test
    void 장애_주입으로_원장에_닿지_않는_500과_202_승인도_승인_호출에_들어간다() throws Exception {
        faults.apply(FaultRules.of(FaultTarget.APPROVE, FaultMode.SERVER_ERROR, 1));
        assertEquals(500, approve(orderId, null).statusCode());
        faults.apply(FaultRules.of(FaultTarget.APPROVE, FaultMode.PROCESSING, 1));
        assertEquals(202, approve(orderId, null).statusCode());

        var view = view();
        assertEquals(2, view.approveCalls());
        // 상태를 저장하지 않았으므로 원장에는 상태가 없고, 주문 수에도 들어가지 않는다
        assertEquals("NONE", view.status());
        assertEquals(0, ledgers.current().summary().orderCount());
    }

    @Test
    void 응답이_유실된_승인도_승인_호출에_들어가고_저장은_되어_있다() throws Exception {
        faults.apply(FaultRules.of(FaultTarget.APPROVE, FaultMode.DROP_RESPONSE, 1));

        assertThrows(IOException.class, () -> approve(orderId, null));

        assertEquals(1, view().approveCalls());
        assertEquals("APPROVED", view().status());
    }

    @Test
    void 조회와_망취소_호출도_장애_주입_응답과_상관없이_센다() throws Exception {
        find(orderId);
        faults.apply(FaultRules.of(FaultTarget.PAYMENT_LOOKUP, FaultMode.SERVER_ERROR, 1));
        assertEquals(500, find(orderId).statusCode());
        faults.apply(FaultRules.of(FaultTarget.CANCEL, FaultMode.PROCESSING, 1));
        assertEquals(202, cancel(orderId).statusCode());
        cancel(orderId);

        var view = view();
        assertEquals(2, view.lookupCalls());
        assertEquals(2, view.cancelCalls());
        assertEquals(0, view.approveCalls());
    }

    @Test
    void 멱등_키는_마지막_값과_서로_다른_값의_수를_기록한다() throws Exception {
        approve(orderId, "key-a");
        approve(orderId, "key-a");
        approve(orderId, "key-b");

        var keys = view().idempotencyKeys();
        assertEquals("key-b", keys.last());
        assertEquals(2, keys.distinctCount());
    }

    @Test
    void 멱등_헤더가_없으면_기록하지_않는다() throws Exception {
        approve(orderId, null);

        var keys = view().idempotencyKeys();
        assertNull(keys.last());
        assertEquals(0, keys.distinctCount());
    }

    @Test
    void 처리_중인_승인은_inFlightCount에_잡히고_끝나면_빠진다() throws Exception {
        var deciding = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var pending = executor.submit(() -> ledgers.current().approve(orderId, null, () -> {
                deciding.countDown();
                await(release);
                return approved(orderId);
            }));
            assertTrue(deciding.await(5, TimeUnit.SECONDS));
            assertEquals(1, ledgers.current().summary().inFlightCount());
            release.countDown();
            pending.get(5, TimeUnit.SECONDS);
        }

        assertEquals(0, ledgers.current().summary().inFlightCount());
    }

    @Test
    void 초기화하면_계수와_요약이_모두_0이다() throws Exception {
        approve(orderId, "key-a");
        find(orderId);
        assertEquals(1, ledgers.current().summary().approvedCount());

        ledgers.reset();

        assertTrue(ledgers.current().orderView(orderId).isEmpty());
        var summary = ledgers.current().summary();
        assertEquals(0, summary.approvedCount());
        assertEquals(0, summary.orderCount());
        assertEquals(0, summary.inFlightCount());
        assertEquals(0, summary.approvedTotal().signum());
    }

    private OrderView view() {
        return ledgers.current().orderView(orderId).orElseThrow();
    }

    private static Approved approved(String orderId) {
        return new Approved(orderId, "pg-" + UUID.randomUUID(), BigDecimal.valueOf(50000), "KRW", Instant.now());
    }

    private HttpResponse<String> approve(String orderId, String idempotencyKey) throws Exception {
        var builder = HttpRequest.newBuilder(uri("/mock-pg/payments")).header("Content-Type", "application/json");
        if (idempotencyKey != null) builder.header("Idempotency-Key", idempotencyKey);
        return send(builder.POST(HttpRequest.BodyPublishers.ofString(
                "{\"orderId\":\"" + orderId + "\",\"amount\":50000,\"currency\":\"KRW\"}")).build());
    }

    private HttpResponse<String> cancel(String orderId) throws Exception {
        return send(HttpRequest.newBuilder(uri("/mock-pg/payments/" + orderId + "/cancel"))
                .POST(HttpRequest.BodyPublishers.noBody()).build());
    }

    private HttpResponse<String> find(String orderId) throws Exception {
        return send(HttpRequest.newBuilder(uri("/mock-pg/payments/" + orderId)).GET().build());
    }

    private HttpResponse<String> send(HttpRequest request) throws Exception {
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }
}
