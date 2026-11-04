package com.iseonjwa.mockpg.fault;

/**
 * 장애를 주입할 호출 종류. 호출 종류마다 설정을 따로 가진다(mock-pg.md 5절).
 */
public enum FaultTarget {
    APPROVE,
    PAYMENT_LOOKUP,
    CANCEL,
    REFUND,
    REFUND_LOOKUP;

    /** 환불 쪽 호출이면 {@code refundId}로, 아니면 {@code orderId}로 대상을 한정한다. */
    boolean isRefund() {
        return this == REFUND || this == REFUND_LOOKUP;
    }
}
