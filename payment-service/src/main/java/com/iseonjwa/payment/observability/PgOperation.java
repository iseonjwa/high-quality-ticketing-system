package com.iseonjwa.payment.observability;

/** PG 호출의 종류. {@code payment.pg.*} 지표의 {@code operation} 라벨 값이다. */
public enum PgOperation {
    APPROVE,
    FIND_PAYMENT,
    REFUND,
    FIND_REFUND,
    PREVENT_LATE_APPROVAL,
    APPROVE_BILLING,
    ISSUE_BILLING_KEY
}
