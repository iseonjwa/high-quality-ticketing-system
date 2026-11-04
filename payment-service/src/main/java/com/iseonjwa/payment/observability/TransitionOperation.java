package com.iseonjwa.payment.observability;

/** 조건부 UPDATE가 0행이 된 경로. {@code payment.transition.conflict}의 {@code operation} 라벨 값이다. */
public enum TransitionOperation {
    /** 승인 API의 TX2 */
    TX2,
    /** 결제 재확인 */
    RECHECK,
    /** 환불 워커 */
    REFUND_WORKER,
    /** 이벤트 소비자 */
    CONSUMER,
    /** DLQ 처리기 */
    DLQ
}
