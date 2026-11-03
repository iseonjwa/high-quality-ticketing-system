package com.iseonjwa.payment.pg.infrastructure;

import com.iseonjwa.payment.observability.PaymentMetrics;
import com.iseonjwa.payment.observability.PgCallOutcome;
import com.iseonjwa.payment.observability.PgOperation;
import com.iseonjwa.payment.pg.application.ApproveCommand;
import com.iseonjwa.payment.pg.application.PgClient;
import com.iseonjwa.payment.pg.application.PgOrderId;
import com.iseonjwa.payment.pg.application.PgPaymentRef;
import com.iseonjwa.payment.pg.application.PgRefundId;
import com.iseonjwa.payment.pg.application.RefundCommand;
import java.util.function.Supplier;

/**
 * 실제 PG 어댑터를 감싸 호출마다 시간과 결과 종류를 잰다(observability.md 1절). 서비스 코드는 PG 지표를 모른다.
 *
 * <p>Note: 결과가 {@code Unknown}이면 원인별 카운터도 올린다. 어댑터가 예외를 던지면 {@code outcome=exception}으로 기록하고
 * 예외를 그대로 다시 던진다. 예외를 Unknown으로 바꾸는 판단은 호출한 서비스의 몫이고 이 클래스는 그 의미를 바꾸지 않는다.
 */
public class MeteredPgClient implements PgClient {
    private final PgClient delegate;
    private final PaymentMetrics metrics;

    public MeteredPgClient(PgClient delegate, PaymentMetrics metrics) {
        this.delegate = delegate;
        this.metrics = metrics;
    }

    @Override
    public ApproveResult approve(ApproveCommand command) {
        return measure(PgOperation.APPROVE, () -> delegate.approve(command));
    }

    @Override
    public PaymentLookupResult findPayment(PgOrderId orderId) {
        return measure(PgOperation.FIND_PAYMENT, () -> delegate.findPayment(orderId));
    }

    @Override
    public RefundResult refund(RefundCommand command) {
        return measure(PgOperation.REFUND, () -> delegate.refund(command));
    }

    @Override
    public RefundLookupResult findRefund(PgRefundId refundId, PgPaymentRef paymentRef) {
        return measure(PgOperation.FIND_REFUND, () -> delegate.findRefund(refundId, paymentRef));
    }

    @Override
    public LateApprovalResult preventLateApproval(PgOrderId orderId) {
        return measure(PgOperation.PREVENT_LATE_APPROVAL, () -> delegate.preventLateApproval(orderId));
    }

    @Override
    public BillingKeyResult issueBillingKey(String authKey, String customerKey) {
        return measure(PgOperation.ISSUE_BILLING_KEY, () -> delegate.issueBillingKey(authKey, customerKey));
    }

    @Override
    public ApproveResult approveBilling(ApproveCommand command, String billingKey) {
        return measure(PgOperation.APPROVE_BILLING, () -> delegate.approveBilling(command, billingKey));
    }

    private <R> R measure(PgOperation operation, Supplier<R> call) {
        var started = System.nanoTime();
        R result;
        try {
            result = call.get();
        } catch (RuntimeException | Error exception) {
            metrics.recordPgCall(operation, PgCallOutcome.EXCEPTION, System.nanoTime() - started);
            throw exception;
        }
        metrics.recordPgCall(operation, PgCallOutcome.of(result), System.nanoTime() - started);
        if (result instanceof Unknown unknown) metrics.pgUnknown(operation, unknown.cause());
        return result;
    }
}
