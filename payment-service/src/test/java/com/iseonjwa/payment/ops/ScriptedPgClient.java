package com.iseonjwa.payment.ops;

import com.iseonjwa.payment.pg.application.ApproveCommand;
import com.iseonjwa.payment.pg.application.PgClient;
import com.iseonjwa.payment.pg.application.PgOrderId;
import com.iseonjwa.payment.pg.application.PgPaymentRef;
import com.iseonjwa.payment.pg.application.PgRefundId;
import com.iseonjwa.payment.pg.application.RefundCommand;

/** inspect 테스트용 PG. 조회 결과를 미리 정해 두고, 조회 말고는 부르면 실패한다(Ops 조사는 PG를 바꾸지 않는다). */
final class ScriptedPgClient implements PgClient {
    PaymentLookupResult payment = new NotFound();
    RefundLookupResult refund = new NotFound();
    RuntimeException failure;

    @Override
    public PaymentLookupResult findPayment(PgOrderId orderId) {
        if (failure != null) throw failure;
        return payment;
    }

    @Override
    public RefundLookupResult findRefund(PgRefundId refundId, PgPaymentRef paymentRef) {
        if (failure != null) throw failure;
        return refund;
    }

    @Override
    public ApproveResult approve(ApproveCommand command) {
        throw new AssertionError("inspect는 승인을 부르지 않는다");
    }

    @Override
    public RefundResult refund(RefundCommand command) {
        throw new AssertionError("Ops 명령은 PG 환불을 부르지 않는다(ADR 0028 결정 4)");
    }

    @Override
    public LateApprovalResult preventLateApproval(PgOrderId orderId) {
        throw new AssertionError("inspect는 늦은 승인 방지를 부르지 않는다");
    }

    @Override
    public BillingKeyResult issueBillingKey(String authKey, String customerKey) {
        throw new AssertionError("inspect는 빌링키를 부르지 않는다");
    }

    @Override
    public ApproveResult approveBilling(ApproveCommand command, String billingKey) {
        throw new AssertionError("inspect는 자동결제를 부르지 않는다");
    }
}
