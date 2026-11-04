package com.iseonjwa.payment.pg.infrastructure;

import com.iseonjwa.payment.payment.domain.DeclineReason;
import com.iseonjwa.payment.pg.application.ApproveCommand;
import com.iseonjwa.payment.pg.application.PgClient;
import com.iseonjwa.payment.pg.application.PgIdempotencyKey;
import com.iseonjwa.payment.pg.application.PgOrderId;
import com.iseonjwa.payment.pg.application.PgPaymentRef;
import com.iseonjwa.payment.pg.application.PgRefundId;
import com.iseonjwa.payment.pg.application.RefundCommand;
import com.iseonjwa.payment.pg.application.UnknownCause;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MockPgAdapter}를 실제 mock-pg 앱에 돌리는 계약 테스트(mock-pg.md 1·10절). 가짜 서버가 아니라 실제 서버의
 * 응답으로 어댑터의 해석을 확인한다.
 *
 * <p>호출 수와 헤더는 서버 원장({@link MockPgLedgerClient})이 센 값으로 단정한다(mock-pg.md 2·9절).
 *
 * <p>TODO: ISE-130에서 공통 계약 {@code PgClientContractTest}를 상속하고, 같은 항목을 검사하는 이 클래스의
 * 테스트는 정리한다. 경과 시간 단정과 이관한 어댑터 고유 테스트는 공통 계약에 없으므로 남긴다.
 */
class MockPgServerContractTest {
    // Note: 응답 유실이 연결이 끊겨서가 아니라 읽기 시한이 지나서 CONNECTION_LOST가 되는 경우를 걸러내려고
    // 경과 시간을 이 값과 비교한다. 첫 호출의 클라이언트 준비 시간이 끼어도 넉넉하게 1초로 둔다
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(1);

    private static final BigDecimal AMOUNT = BigDecimal.valueOf(50000);
    private static final String KRW = "KRW";

    private static MockPgProcess server;
    private static PgClient client;

    @BeforeAll
    static void startServer() {
        server = MockPgProcess.start();
        client = MockPgAdapter.create(server.baseUrl(), Duration.ofMillis(500), READ_TIMEOUT);
    }

    @AfterAll
    static void stopServer() {
        if (server != null) server.close();
    }

    // 요약과 계수는 서버 전체를 세므로 테스트마다 새 원장에서 시작한다. 테스트가 장애를 소비하기 전에 실패해도
    // 남은 설정이 다음 테스트의 호출을 바꾸지 않게 reset이 장애 설정도 함께 지운다
    @BeforeEach
    @AfterEach
    void resetServer() {
        server.ledger().reset();
    }

    @Test
    void 처음_보는_주문을_조회하면_NotFound다() {
        assertInstanceOf(PgClient.NotFound.class, client.findPayment(newOrder()));
    }

    @Test
    void 응답이_유실되면_읽기_시한을_기다리지_않고_CONNECTION_LOST인_Unknown이다() {
        server.applyFault("APPROVE", "DROP_RESPONSE", 1);

        var startedAt = System.nanoTime();
        var result = client.approve(approveCommand(newOrder()));
        var elapsed = Duration.ofNanos(System.nanoTime() - startedAt);

        assertEquals(new PgClient.Unknown(UnknownCause.CONNECTION_LOST), result);
        assertTrue(elapsed.compareTo(READ_TIMEOUT) < 0, "읽기 시한이 지나서 끊겼다 elapsed=" + elapsed);
    }

    @Test
    void 응답을_잃은_승인은_조회로_확인한다() {
        var order = newOrder();
        server.applyFault("APPROVE", "DROP_RESPONSE", 1);
        assertEquals(new PgClient.Unknown(UnknownCause.CONNECTION_LOST), client.approve(approveCommand(order)));

        var found = assertInstanceOf(PgClient.Approved.class, client.findPayment(order));

        assertTrue(found.matches(BigDecimal.valueOf(50000), "KRW"));
    }

    @Test
    void 승인되지_않은_주문은_늦은_승인을_막고_이후에도_승인으로_조회되지_않는다() {
        var order = newOrder();

        assertInstanceOf(PgClient.Prevented.class, client.preventLateApproval(order));

        assertInstanceOf(PgClient.NotFound.class, client.findPayment(order));
    }

    @Test
    void 이미_승인된_주문은_늦은_승인_방지가_승인_사실을_알린다() {
        var order = newOrder();
        var approved = assertInstanceOf(PgClient.Approved.class, client.approve(approveCommand(order)));

        var already = assertInstanceOf(PgClient.AlreadyApproved.class, client.preventLateApproval(order));

        assertEquals(approved.paymentRef(), already.paymentRef());
        assertTrue(already.matches(BigDecimal.valueOf(50000), "KRW"));
    }

    // 이관: MockPgAdapterContractTest(가짜 서버)에서 옮겼다(mock-pg.md 1절)
    @Test
    void 망취소된_주문의_승인은_PG가_거부하므로_NOT_SENT로_거절한다() {
        var order = newOrder();
        assertInstanceOf(PgClient.Prevented.class, client.preventLateApproval(order));

        var declined = assertInstanceOf(PgClient.Declined.class, client.approve(approveCommand(order)));

        assertEquals(DeclineReason.NOT_SENT, declined.reason());
    }

    @Test
    void 환불하면_PG가_처리한_금액과_통화를_돌려준다() {
        var approved = approve();

        var refunded = assertInstanceOf(PgClient.Refunded.class, client.refund(refundCommand(approved, newRefund())));

        assertTrue(refunded.matches(AMOUNT, KRW));
    }

    @Test
    void 환불_조회가_NotFound이면_같은_환불_식별자로_다시_보내도_한_번만_환불된다() {
        var approved = approve();
        var refundId = newRefund();
        assertInstanceOf(PgClient.NotFound.class, client.findRefund(refundId, approved.paymentRef()));

        var first = assertInstanceOf(PgClient.Refunded.class, client.refund(refundCommand(approved, refundId)));
        var second = assertInstanceOf(PgClient.Refunded.class, client.refund(refundCommand(approved, refundId)));

        assertFalse(first.refundRef().isBlank());
        assertEquals(first.refundRef(), second.refundRef());
    }

    // 이관: MockPgAdapterContractTest(가짜 서버)에서 옮겼다(mock-pg.md 1절)
    @Test
    void 다른_결제의_환불_응답은_환불_완료로_받지_않는다() {
        var approved = approve();
        var refundId = newRefund();
        client.refund(refundCommand(approved, refundId));

        var result = client.findRefund(refundId, new PgPaymentRef("pg-other"));

        assertEquals(new PgClient.Unknown(UnknownCause.PG_ERROR), result);
    }

    // 이관: MockPgAdapterContractTest(가짜 서버)에서 옮겼다(mock-pg.md 1절). 호출 수는 서버가 센 approveCalls로 단정한다
    @Test
    void 같은_주문을_여러_번_승인해도_PG_원장에는_한_번만_남고_호출은_센_만큼_남는다() {
        var order = newOrder();

        var first = assertInstanceOf(PgClient.Approved.class, client.approve(approveCommand(order)));
        var second = assertInstanceOf(PgClient.Approved.class, client.approve(approveCommand(order)));

        assertEquals(first.paymentRef(), second.paymentRef());
        var ledger = server.ledger().payment(order.value().toString()).orElseThrow();
        assertEquals(2, ledger.approveCalls());
        assertEquals(first.paymentRef().value(), ledger.pgTxId());
        assertEquals(1, server.ledger().summary().approvedCount());
    }

    @Test
    void 어댑터는_승인을_한_번_부르면_PG에_한_번만_보낸다_응답이_유실돼도_다시_보내지_않는다() {
        var lost = newOrder();
        server.applyFault("APPROVE", "DROP_RESPONSE", 1);
        assertEquals(new PgClient.Unknown(UnknownCause.CONNECTION_LOST), client.approve(approveCommand(lost)));
        var serverError = newOrder();
        server.applyFault("APPROVE", "SERVER_ERROR", 1);
        assertEquals(new PgClient.Unknown(UnknownCause.PG_ERROR), client.approve(approveCommand(serverError)));

        assertEquals(1, server.ledger().payment(lost.value().toString()).orElseThrow().approveCalls());
        // 500도 호출로 센다. 그리고 원장에는 상태가 없다
        var failed = server.ledger().payment(serverError.value().toString()).orElseThrow();
        assertEquals(1, failed.approveCalls());
        assertEquals("NONE", failed.status());
    }

    // 이관: MockPgAdapterContractTest(가짜 서버)에서 옮겼다(mock-pg.md 1절). 원장이 헤더를 기록한다
    @Test
    void 승인_호출은_주문에서_파생한_결정적_멱등키를_헤더로_보낸다() {
        var order = newOrder();

        client.approve(approveCommand(order));
        client.approve(approveCommand(order));

        var ledger = server.ledger().payment(order.value().toString()).orElseThrow();
        assertEquals(order.value().toString(), ledger.lastIdempotencyKey());
        assertEquals(1, ledger.distinctIdempotencyKeys());
    }

    // 이관: MockPgAdapterContractTest(가짜 서버)에서 옮겼다(mock-pg.md 1절). 호출 수는 서버가 센 refundCalls로 단정한다
    @Test
    void 환불은_같은_환불_식별자로_여러_번_보내도_PG_원장에는_한_번만_남고_호출은_센_만큼_남는다() {
        var approved = approve();
        var refundId = newRefund();

        var first = assertInstanceOf(PgClient.Refunded.class, client.refund(refundCommand(approved, refundId)));
        var second = assertInstanceOf(PgClient.Refunded.class, client.refund(refundCommand(approved, refundId)));

        assertEquals(first.refundRef(), second.refundRef());
        var refunds = server.ledger().refunds(approved.paymentRef().value());
        assertEquals(1, refunds.refundCount());
        assertEquals(0, AMOUNT.compareTo(refunds.refundedTotal()));
        var refund = refunds.refunds().getFirst();
        assertEquals(refundId.value().toString(), refund.refundId());
        assertEquals(2, refund.refundCalls());
        assertEquals(refundId.value().toString(), refund.lastIdempotencyKey());
        assertEquals(1, refund.distinctIdempotencyKeys());
    }

    @Test
    void 환불_응답이_유실돼도_PG에는_한_번만_보내고_저장되어_있다() {
        var approved = approve();
        var refundId = newRefund();
        server.applyFault("REFUND", "DROP_RESPONSE", 1);

        assertEquals(new PgClient.Unknown(UnknownCause.CONNECTION_LOST), client.refund(refundCommand(approved, refundId)));

        var refund = server.ledger().refunds(approved.paymentRef().value()).refunds().getFirst();
        assertEquals(1, refund.refundCalls());
        // 환불 워커의 "재호출 전 조회" 규칙: 저장된 환불은 조회로 확인된다
        assertInstanceOf(PgClient.Refunded.class, client.findRefund(refundId, approved.paymentRef()));
        assertEquals(1, server.ledger().refunds(approved.paymentRef().value()).refunds().getFirst().refundLookupCalls());
    }

    @Test
    void 지연된_승인은_inFlightCount로_끝날_때까지_기다릴_수_있다() {
        var order = newOrder();
        server.applyDelay("APPROVE", 1_500, 1);

        assertEquals(new PgClient.Unknown(UnknownCause.TIMEOUT), client.approve(approveCommand(order)));
        // 클라이언트는 포기했지만 PG 쪽 처리는 계속 진행 중이다
        assertEquals(1, server.ledger().summary().inFlightCount());
        server.ledger().awaitNoInFlight(Duration.ofSeconds(10));

        assertEquals(0, server.ledger().summary().inFlightCount());
        assertInstanceOf(PgClient.Approved.class, client.findPayment(order));
    }

    @Test
    void 승인된_주문_목록은_쪽을_넘겨_모두_모은다() {
        var orders = new java.util.ArrayList<String>();
        for (int i = 0; i < 3; i++) {
            var order = newOrder();
            client.approve(approveCommand(order));
            orders.add(order.value().toString());
        }

        var approved = server.ledger().approvedOrders();

        assertEquals(orders, approved.stream().map(MockPgLedgerClient.ApprovedOrder::orderId).toList());
    }

    // 빌링키 발급과 자동결제(ISE-96). 자동결제는 승인과 같은 주문 원장을 쓰고, 발급하지 않은 빌링키는 거절한다
    @Test
    void 빌링키를_발급받아_자동결제하면_승인되고_조회도_된다() {
        var issued = assertInstanceOf(PgClient.BillingKeyIssued.class, client.issueBillingKey("auth-1", "customer-1"));
        var order = newOrder();

        var approved = assertInstanceOf(PgClient.Approved.class,
                client.approveBilling(approveCommand(order), issued.billingKey()));

        assertEquals("4242", issued.cardLast4());
        assertEquals(0, AMOUNT.compareTo(approved.amount()));
        assertInstanceOf(PgClient.Approved.class, client.findPayment(order));
    }

    @Test
    void 인증_값이_거절되면_BillingKeyRejected이고_모르는_빌링키_자동결제는_BILLING_KEY_MISSING이다() {
        assertEquals(new PgClient.BillingKeyRejected(), client.issueBillingKey("REJECT-auth", "customer-1"));
        assertEquals(new PgClient.Declined(DeclineReason.BILLING_KEY_MISSING),
                client.approveBilling(approveCommand(newOrder()), "bk-never-issued"));
    }

    private PgClient.Approved approve() {
        return assertInstanceOf(PgClient.Approved.class, client.approve(approveCommand(newOrder())));
    }

    private static PgRefundId newRefund() {
        return new PgRefundId(UUID.randomUUID());
    }

    private static RefundCommand refundCommand(PgClient.Approved approved, PgRefundId refundId) {
        return new RefundCommand(approved.paymentRef(), refundId, AMOUNT, KRW, "CANCELED",
                PgIdempotencyKey.ofRefund(refundId));
    }

    private static PgOrderId newOrder() {
        return new PgOrderId(UUID.randomUUID());
    }

    private static ApproveCommand approveCommand(PgOrderId order) {
        return new ApproveCommand(order, AMOUNT, KRW, PgIdempotencyKey.ofApproval(order), null);
    }
}
