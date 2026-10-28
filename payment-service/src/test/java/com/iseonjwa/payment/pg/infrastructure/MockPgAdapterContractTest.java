package com.iseonjwa.payment.pg.infrastructure;

import com.iseonjwa.payment.payment.domain.DeclineReason;
import com.iseonjwa.payment.pg.PgClientContractTest;
import com.iseonjwa.payment.pg.PgScenario;
import com.iseonjwa.payment.pg.application.PgClient;
import com.iseonjwa.payment.pg.application.PgPaymentRef;
import com.iseonjwa.payment.pg.application.UnknownCause;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static com.iseonjwa.payment.pg.infrastructure.FakeMockPgServer.Fault;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

// Note: 응답 시간 제한을 짧게 줘 느린 응답 시나리오가 빨리 끝나게 한다. 운영 값(2초)은 PgProperties 기본값이다.
class MockPgAdapterContractTest extends PgClientContractTest {
    private static final Duration READ_TIMEOUT = Duration.ofMillis(300);
    private static final Duration SLOW_DELAY = Duration.ofMillis(900);

    private static FakeMockPgServer server;
    private static PgClient client;

    private final PgScenario scenario = new PgScenario() {
        public void nextApprovalIsDeclined() { server.failNextApproval(Fault.DECLINE); }
        public void nextApprovalIsSlow() { server.delayNextApproval(SLOW_DELAY.toMillis()); }
        public void nextApprovalResponseIsLost() { server.failNextApproval(Fault.DROP_RESPONSE); }
        public void nextApprovalIsProcessing() { server.failNextApproval(Fault.PROCESSING); }
        public void nextApprovalIsBadRequest() { server.failNextApproval(Fault.BAD_REQUEST); }
        public void nextApprovalIsServerError() { server.failNextApproval(Fault.SERVER_ERROR); }
        public void nextApprovalHasFractionalAmount() { server.failNextApproval(Fault.FRACTIONAL_AMOUNT); }
        public void nextRefundOmitsAmountAndCurrency() { server.failNextRefund(Fault.MISSING_AMOUNT); }
        public void awaitPendingEffects() { sleep(SLOW_DELAY.toMillis() + 300); }
        public void nextApprovalOmitsAmountAndCurrency() { server.failNextApproval(Fault.MISSING_AMOUNT); }
        public void nextApprovalUsesDifferentAmount() { server.failNextApproval(Fault.WRONG_AMOUNT); }
        public void nextRefundIsRejected() { server.rejectNextRefund(); }
        public void nextRefundResponseIsLost() { server.failNextRefund(Fault.DROP_RESPONSE); }
        public void nextRefundIsProcessing() { server.failNextRefund(Fault.PROCESSING); }
    };

    @BeforeAll
    static void startServer() {
        server = new FakeMockPgServer();
        client = MockPgAdapter.create(server.baseUrl(), Duration.ofMillis(500), READ_TIMEOUT);
    }

    @AfterAll
    static void stopServer() {
        server.close();
    }

    @Override
    protected PgClient client() {
        return client;
    }

    @Override
    protected PgScenario scenario() {
        return scenario;
    }

    @Test
    void 같은_주문을_여러_번_승인해도_PG_원장에는_한_번만_남는다() {
        var order = newOrder();

        client().approve(approveCommand(order));
        client().approve(approveCommand(order));

        assertEquals(1, server.approvalCount(order.value().toString()));
    }

    @Test
    void 승인_호출은_주문에서_파생한_결정적_멱등키를_헤더로_보낸다() {
        var order = newOrder();

        client().approve(approveCommand(order));

        assertEquals(order.value().toString(), server.approvalIdempotencyKey(order.value().toString()));
    }

    @Test
    void 환불은_같은_환불_식별자로_여러_번_보내도_PG_원장에는_한_번만_남는다() {
        var approved = assertInstanceOf(PgClient.Approved.class, client().approve(approveCommand(newOrder())));
        var refundId = newRefund();

        client().refund(refundCommand(approved, refundId));
        client().refund(refundCommand(approved, refundId));

        assertEquals(1, server.refundCount(refundId.value().toString()));
    }

    @Test
    void 망취소된_주문의_승인은_PG가_거부하므로_NOT_SENT로_거절한다() {
        var order = newOrder();
        client().preventLateApproval(order);

        var declined = assertInstanceOf(PgClient.Declined.class, client().approve(approveCommand(order)));

        assertEquals(DeclineReason.NOT_SENT, declined.reason());
    }

    @Test
    void 망취소_코드가_아닌_409는_거절이_아니라_PROCESSING인_Unknown이다() {
        server.failNextApproval(Fault.OTHER_CONFLICT);

        assertEquals(new PgClient.Unknown(UnknownCause.PROCESSING), client().approve(approveCommand(newOrder())));
    }

    @Test
    void 해당_주문_없음_코드가_없는_404는_NotFound가_아니라_CLIENT_ERROR인_Unknown이다() {
        server.answerLookupWithBareNotFound();

        assertEquals(new PgClient.Unknown(UnknownCause.CLIENT_ERROR), client().findPayment(newOrder()));
    }

    @Test
    void 다른_결제의_환불_응답은_환불_완료로_받지_않는다() {
        var approved = assertInstanceOf(PgClient.Approved.class, client().approve(approveCommand(newOrder())));
        var refundId = newRefund();
        client().refund(refundCommand(approved, refundId));

        var result = client().findRefund(refundId, new PgPaymentRef("pg-other"));

        assertEquals(new PgClient.Unknown(UnknownCause.PG_ERROR), result);
    }

    @Test
    void 연결할_수_없으면_CONNECTION_LOST인_Unknown이다() {
        var unreachable = MockPgAdapter.create("http://127.0.0.1:1", Duration.ofMillis(500), READ_TIMEOUT);

        var result = unreachable.approve(approveCommand(newOrder()));

        assertEquals(new PgClient.Unknown(UnknownCause.CONNECTION_LOST), result);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}
