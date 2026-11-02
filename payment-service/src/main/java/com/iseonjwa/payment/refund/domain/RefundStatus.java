package com.iseonjwa.payment.refund.domain;

/**
 * 환불 상태. 전이표는 docs/design/payment/domain-and-states.md 2절을 따른다.
 */
public enum RefundStatus {
    REQUESTED,
    UNKNOWN,
    REFUNDED,
    NEEDS_MANUAL_CHECK,
    FAILED
}
