package com.iseonjwa.payment.pg.application;

import com.iseonjwa.payment.payment.domain.DeclineReason;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * PG 호출 포트(ADR 0023). 서비스 코드는 PG별 요청·응답·에러 코드를 보지 못하고 이 포트의 결과 타입만 본다.
 * HTTP 상태와 에러 코드의 해석은 어댑터 안에서 끝낸다.
 *
 * <p>Note: 결과를 모르면 거절이 아니라 {@link Unknown}이다. 타임아웃을 거절로 닫으면 PG에서는 승인된 결제가
 * 우리 쪽에서 실패로 남아 돈이 묶인다(INV-8). 호출 규칙: {@link #approve}는 결제 행 하나에 한 번만 부르고,
 * {@link #refund}는 환불 워커만 부르며 다시 부르기 전에 {@link #findRefund}로 먼저 확인한다.
 *
 * <p>PG 결과는 예외로 알리지 않고 결과 타입으로 돌려준다. 그래도 예외가 나오면 호출한 쪽은 결과를 모르는
 * 것(Unknown)으로 다뤄야 하며 거절로 닫지 않는다.
 */
public interface PgClient {
    ApproveResult approve(ApproveCommand command);

    PaymentLookupResult findPayment(PgOrderId orderId);

    RefundResult refund(RefundCommand command);

    RefundLookupResult findRefund(PgRefundId refundId, PgPaymentRef paymentRef);

    // 승인 여부가 불확실한 주문이 나중에 승인되지 않음을 보장하거나, 이미 승인됐음을 알린다.
    LateApprovalResult preventLateApproval(PgOrderId orderId);

    sealed interface ApproveResult {}

    sealed interface PaymentLookupResult {}

    sealed interface RefundResult {}

    sealed interface RefundLookupResult {}

    sealed interface LateApprovalResult {}

    /** PG가 승인했다. 금액·통화는 PG가 응답한 값이라 서비스가 결제와 대조한다(INV-3). */
    record Approved(PgPaymentRef paymentRef, BigDecimal amount, String currency, Instant approvedAt)
            implements ApproveResult, PaymentLookupResult {
        public boolean matches(BigDecimal expectedAmount, String expectedCurrency) {
            return PgAmounts.sameAmount(expectedAmount, amount) && expectedCurrency.equals(currency);
        }
    }

    /** PG가 명확히 거절했다. 결과를 알 수 없는 경우는 여기에 넣지 않는다. */
    record Declined(DeclineReason reason) implements ApproveResult, PaymentLookupResult {}

    record Refunded(String refundRef, BigDecimal amount, String currency, Instant refundedAt)
            implements RefundResult, RefundLookupResult {
        public boolean matches(BigDecimal expectedAmount, String expectedCurrency) {
            return PgAmounts.sameAmount(expectedAmount, amount) && expectedCurrency.equals(currency);
        }
    }

    record RefundRejected(RefundRejectReason reason) implements RefundResult, RefundLookupResult {}

    /** PG에 해당 주문·환불이 없다. 요청이 PG에 닿지 않았을 가능성이 있지만 이것만으로 거절로 닫지 않는다. */
    record NotFound() implements PaymentLookupResult, RefundLookupResult {}

    record Prevented() implements LateApprovalResult {}

    record AlreadyApproved(PgPaymentRef paymentRef, BigDecimal amount, String currency, Instant approvedAt)
            implements LateApprovalResult {
        // 늦은 승인 방지 경로에서도 승인 금액·통화를 결제와 대조한다(INV-3)
        public boolean matches(BigDecimal expectedAmount, String expectedCurrency) {
            return PgAmounts.sameAmount(expectedAmount, amount) && expectedCurrency.equals(currency);
        }
    }

    record Unknown(UnknownCause cause)
            implements ApproveResult, PaymentLookupResult, RefundResult, RefundLookupResult, LateApprovalResult {}
}
