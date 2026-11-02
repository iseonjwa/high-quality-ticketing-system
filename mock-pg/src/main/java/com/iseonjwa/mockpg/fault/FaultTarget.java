package com.iseonjwa.mockpg.fault;

/**
 * 장애를 주입할 호출 종류. 호출 종류마다 설정을 따로 가진다(mock-pg.md 5절).
 * 환불(REFUND·REFUND_LOOKUP)은 ISE-132·133에서 더한다.
 */
public enum FaultTarget {
    APPROVE,
    PAYMENT_LOOKUP,
    CANCEL
}
