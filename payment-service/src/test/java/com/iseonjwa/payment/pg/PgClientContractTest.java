package com.iseonjwa.payment.pg;

import com.iseonjwa.payment.payment.domain.DeclineReason;
import com.iseonjwa.payment.pg.application.ApproveCommand;
import com.iseonjwa.payment.pg.application.PgClient;
import com.iseonjwa.payment.pg.application.PgClient.AlreadyApproved;
import com.iseonjwa.payment.pg.application.PgClient.Approved;
import com.iseonjwa.payment.pg.application.PgClient.Declined;
import com.iseonjwa.payment.pg.application.PgClient.NotFound;
import com.iseonjwa.payment.pg.application.PgClient.Prevented;
import com.iseonjwa.payment.pg.application.PgClient.RefundRejected;
import com.iseonjwa.payment.pg.application.PgClient.Refunded;
import com.iseonjwa.payment.pg.application.PgClient.Unknown;
import com.iseonjwa.payment.pg.application.PgIdempotencyKey;
import com.iseonjwa.payment.pg.application.PgOrderId;
import com.iseonjwa.payment.pg.application.PgRefundId;
import com.iseonjwa.payment.pg.application.RefundCommand;
import com.iseonjwa.payment.pg.application.RefundRejectReason;
import com.iseonjwa.payment.pg.application.UnknownCause;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 모든 {@link PgClient} 어댑터가 통과해야 하는 공통 계약(ADR 0023 결정 9).
 * 어댑터별 테스트 클래스가 이 클래스를 상속해 {@link #client()}와 {@link #scenario()}만 채운다.
 */
public abstract class PgClientContractTest {
    protected static final BigDecimal AMOUNT = BigDecimal.valueOf(50000);
    protected static final String KRW = "KRW";

    protected abstract PgClient client();

    protected abstract PgScenario scenario();

    @Test
    void 승인하면_PG가_처리한_금액과_통화를_돌려준다() {
        var approved = assertInstanceOf(Approved.class, client().approve(approveCommand(newOrder())));

        assertTrue(approved.matches(AMOUNT, KRW));
        assertFalse(approved.paymentRef().value().isBlank());
        assertTrue(approved.approvedAt() != null);
    }

    @Test
    void PG가_명확히_거절하면_Declined다() {
        scenario().nextApprovalIsDeclined();

        var declined = assertInstanceOf(Declined.class, client().approve(approveCommand(newOrder())));

        assertEquals(DeclineReason.CARD_DECLINED, declined.reason());
    }

    @Test
    void 응답이_늦으면_거절이_아니라_TIMEOUT인_Unknown이다() {
        scenario().nextApprovalIsSlow();

        assertUnknown(UnknownCause.TIMEOUT, client().approve(approveCommand(newOrder())));
    }

    @Test
    void 응답이_유실되면_CONNECTION_LOST인_Unknown이다() {
        scenario().nextApprovalResponseIsLost();

        assertUnknown(UnknownCause.CONNECTION_LOST, client().approve(approveCommand(newOrder())));
    }

    @Test
    void 응답을_잃은_뒤_같은_멱등키로_다시_보내면_같은_승인을_돌려준다() {
        var order = newOrder();
        scenario().nextApprovalResponseIsLost();
        assertUnknown(UnknownCause.CONNECTION_LOST, client().approve(approveCommand(order)));

        var retried = assertInstanceOf(Approved.class, client().approve(approveCommand(order)));
        var again = assertInstanceOf(Approved.class, client().approve(approveCommand(order)));

        assertEquals(retried.paymentRef(), again.paymentRef());
    }

    @Test
    void 응답을_잃은_승인은_조회로_확인한다() {
        var order = newOrder();
        scenario().nextApprovalResponseIsLost();
        client().approve(approveCommand(order));

        var found = assertInstanceOf(Approved.class, client().findPayment(order));

        assertTrue(found.matches(AMOUNT, KRW));
    }

    @Test
    void 처음_보는_주문을_조회하면_NotFound다() {
        assertInstanceOf(NotFound.class, client().findPayment(newOrder()));
    }

    @Test
    void PG가_처리_중이라고_하면_PROCESSING인_Unknown이다() {
        scenario().nextApprovalIsProcessing();

        assertUnknown(UnknownCause.PROCESSING, client().approve(approveCommand(newOrder())));
    }

    @Test
    void 요청_형식을_거절하면_거절이_아니라_CLIENT_ERROR인_Unknown이다() {
        scenario().nextApprovalIsBadRequest();

        assertUnknown(UnknownCause.CLIENT_ERROR, client().approve(approveCommand(newOrder())));
    }

    @Test
    void 응답에_금액과_통화가_없으면_값을_지어내지_않고_PG_ERROR인_Unknown이다() {
        scenario().nextApprovalOmitsAmountAndCurrency();

        assertUnknown(UnknownCause.PG_ERROR, client().approve(approveCommand(newOrder())));
    }

    @Test
    void PG가_요청과_다른_금액을_승인하면_PG의_금액으로_구분할_수_있다() {
        scenario().nextApprovalUsesDifferentAmount();

        var approved = assertInstanceOf(Approved.class, client().approve(approveCommand(newOrder())));

        assertFalse(approved.matches(AMOUNT, KRW), "서비스가 불일치를 알아볼 수 있어야 한다");
        assertEquals(0, BigDecimal.valueOf(49000).compareTo(approved.amount()));
    }

    @Test
    void 환불하면_PG가_처리한_금액과_통화를_돌려준다() {
        var approved = approve();

        var refunded = assertInstanceOf(Refunded.class, client().refund(refundCommand(approved, newRefund())));

        assertTrue(refunded.matches(AMOUNT, KRW));
    }

    @Test
    void PG가_환불을_거절하면_RefundRejected다() {
        var approved = approve();
        scenario().nextRefundIsRejected();

        var rejected = assertInstanceOf(RefundRejected.class, client().refund(refundCommand(approved, newRefund())));

        assertEquals(RefundRejectReason.NOT_REFUNDABLE, rejected.reason());
    }

    @Test
    void 환불_응답이_유실되면_Unknown이고_조회로_환불_완료를_확인한다() {
        var approved = approve();
        var refundId = newRefund();
        scenario().nextRefundResponseIsLost();

        assertUnknown(UnknownCause.CONNECTION_LOST, client().refund(refundCommand(approved, refundId)));

        var found = assertInstanceOf(Refunded.class, client().findRefund(refundId, approved.paymentRef()));
        assertTrue(found.matches(AMOUNT, KRW));
    }

    @Test
    void 환불_조회가_NotFound이면_같은_환불_식별자로_다시_보내도_한_번만_환불된다() {
        var approved = approve();
        var refundId = newRefund();
        assertInstanceOf(NotFound.class, client().findRefund(refundId, approved.paymentRef()));

        var first = assertInstanceOf(Refunded.class, client().refund(refundCommand(approved, refundId)));
        var second = assertInstanceOf(Refunded.class, client().refund(refundCommand(approved, refundId)));

        assertEquals(first.refundRef(), second.refundRef());
    }

    @Test
    void 환불이_처리_중이라고_하면_PROCESSING인_Unknown이다() {
        var approved = approve();
        scenario().nextRefundIsProcessing();

        assertUnknown(UnknownCause.PROCESSING, client().refund(refundCommand(approved, newRefund())));
    }

    @Test
    void 승인되지_않은_주문은_늦은_승인을_막고_이후_승인을_거부한다() {
        var order = newOrder();

        assertInstanceOf(Prevented.class, client().preventLateApproval(order));

        var declined = assertInstanceOf(Declined.class, client().approve(approveCommand(order)));
        assertEquals(DeclineReason.NOT_SENT, declined.reason());
    }

    @Test
    void 이미_승인된_주문은_늦은_승인_방지가_승인_사실을_알린다() {
        var order = newOrder();
        client().approve(approveCommand(order));

        var already = assertInstanceOf(AlreadyApproved.class, client().preventLateApproval(order));

        assertEquals(0, AMOUNT.compareTo(already.amount()));
    }

    protected final PgOrderId newOrder() {
        return new PgOrderId(UUID.randomUUID());
    }

    protected final PgRefundId newRefund() {
        return new PgRefundId(UUID.randomUUID());
    }

    protected final ApproveCommand approveCommand(PgOrderId order) {
        return new ApproveCommand(order, AMOUNT, KRW, PgIdempotencyKey.ofApproval(order), null);
    }

    protected final RefundCommand refundCommand(Approved approved, PgRefundId refundId) {
        return new RefundCommand(approved.paymentRef(), refundId, AMOUNT, KRW, "CANCELED",
                PgIdempotencyKey.ofRefund(refundId));
    }

    private Approved approve() {
        return assertInstanceOf(Approved.class, client().approve(approveCommand(newOrder())));
    }

    private static void assertUnknown(UnknownCause expected, Object result) {
        assertEquals(expected, assertInstanceOf(Unknown.class, result).cause());
    }
}
