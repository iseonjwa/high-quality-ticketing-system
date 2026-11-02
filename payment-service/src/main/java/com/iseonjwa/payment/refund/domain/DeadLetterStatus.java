package com.iseonjwa.payment.refund.domain;

/** DLQ 메시지의 처리 상태. {@code OPEN}은 환불 의무를 확인하지 못한 미해결 상태다(INV-4). */
public enum DeadLetterStatus {
    OPEN,
    RESOLVED
}
