package com.iseonjwa.payment.ops;

import com.iseonjwa.payment.pg.application.ApproveCommand;
import com.iseonjwa.payment.pg.application.PgClient;
import com.iseonjwa.payment.pg.application.PgClients;
import com.iseonjwa.payment.support.StubPgClients;
import com.iseonjwa.payment.pg.application.PgOrderId;
import com.iseonjwa.payment.pg.application.PgPaymentRef;
import com.iseonjwa.payment.pg.application.PgRefundId;
import com.iseonjwa.payment.pg.application.RefundCommand;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Ops 테스트용 PG. 조회 결과를 미리 정해 두고 호출 횟수를 센다. 늦은 승인 방지는 resolve 테스트가 쓰고, 환불·승인 같은
 * 나머지는 부르면 실패한다(Ops 명령은 PG 환불을 부르지 않는다, ADR 0028 결정 4).
 */
final class ScriptedPgClient implements PgClient {
    PaymentLookupResult payment = new NotFound();
    RefundLookupResult refund = new NotFound();
    LateApprovalResult lateApproval = new Prevented();
    RuntimeException failure;
    int findPaymentCalls;
    int findRefundCalls;
    int preventLateCalls;
    int refundCalls;

    ObjectProvider<PgClients> asProvider() {
        var client = new StubPgClients(this);
        return new ObjectProvider<>() {
            @Override
            public PgClients getObject() { return client; }

            @Override
            public PgClients getObject(Object... args) { return client; }

            @Override
            public PgClients getIfAvailable() { return client; }

            @Override
            public PgClients getIfUnique() { return client; }
        };
    }

    @Override
    public PaymentLookupResult findPayment(PgOrderId orderId) {
        findPaymentCalls++;
        if (failure != null) throw failure;
        return payment;
    }

    @Override
    public RefundLookupResult findRefund(PgRefundId refundId, PgPaymentRef paymentRef) {
        findRefundCalls++;
        if (failure != null) throw failure;
        return refund;
    }

    @Override
    public ApproveResult approve(ApproveCommand command) {
        throw new AssertionError("inspect는 승인을 부르지 않는다");
    }

    @Override
    public RefundResult refund(RefundCommand command) {
        refundCalls++;
        throw new AssertionError("Ops 명령은 PG 환불을 부르지 않는다(ADR 0028 결정 4)");
    }

    @Override
    public LateApprovalResult preventLateApproval(PgOrderId orderId) {
        preventLateCalls++;
        if (failure != null) throw failure;
        return lateApproval;
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
