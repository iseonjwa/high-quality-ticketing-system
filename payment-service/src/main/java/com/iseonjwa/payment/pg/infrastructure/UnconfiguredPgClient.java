package com.iseonjwa.payment.pg.infrastructure;

import com.iseonjwa.payment.payment.domain.DeclineReason;
import com.iseonjwa.payment.payment.domain.PgProvider;
import com.iseonjwa.payment.pg.application.ApproveCommand;
import com.iseonjwa.payment.pg.application.PgClient;
import com.iseonjwa.payment.pg.application.PgOrderId;
import com.iseonjwa.payment.pg.application.PgPaymentRef;
import com.iseonjwa.payment.pg.application.PgRefundId;
import com.iseonjwa.payment.pg.application.RefundCommand;
import com.iseonjwa.payment.pg.application.UnknownCause;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 설정에 없는 PG(토스 키 없이 {@code TOSS} 결제)를 달라고 했을 때 돌려주는 클라이언트. PG를 부르지 않고 바로
 * {@code Unknown(CLIENT_ERROR)}로 답한다.
 *
 * <p>Note: 우리 쪽 설정 문제라 {@code CLIENT_ERROR}다. {@code PG_ERROR}로 답하면 공유 복구 서킷 브레이커가 실패로 세어
 * 토스 건 몇 개만으로 CB가 열리고, Mock 결제의 재확인·환불까지 멈춘다. {@code CLIENT_ERROR}는 허가를 반환하고 세지 않는다.
 */
final class UnconfiguredPgClient implements PgClient {
    private static final Logger log = LoggerFactory.getLogger(UnconfiguredPgClient.class);

    private final PgProvider provider;

    UnconfiguredPgClient(PgProvider provider) {
        this.provider = provider;
    }

    @Override
    public ApproveResult approve(ApproveCommand command) {
        return unknown("approve");
    }

    @Override
    public PaymentLookupResult findPayment(PgOrderId orderId) {
        return unknown("findPayment");
    }

    @Override
    public RefundResult refund(RefundCommand command) {
        return unknown("refund");
    }

    @Override
    public RefundLookupResult findRefund(PgRefundId refundId, PgPaymentRef paymentRef) {
        return unknown("findRefund");
    }

    @Override
    public LateApprovalResult preventLateApproval(PgOrderId orderId) {
        return unknown("preventLateApproval");
    }

    @Override
    public BillingKeyResult issueBillingKey(String authKey, String customerKey) {
        return unknown("issueBillingKey");
    }

    // 보내지 않았으므로 확실히 승인되지 않았다(토스 어댑터와 같은 규칙). 빌링은 늘 Mock이라 실제로는 부르지 않는다
    @Override
    public ApproveResult approveBilling(ApproveCommand command, String billingKey) {
        logUnconfigured("approveBilling");
        return new Declined(DeclineReason.NOT_SENT);
    }

    private Unknown unknown(String method) {
        logUnconfigured(method);
        return new Unknown(UnknownCause.CLIENT_ERROR);
    }

    private void logUnconfigured(String method) {
        log.error("{} PG가 설정되지 않아 호출하지 못했다(토스 키 없음) method={}", provider, method);
    }
}
