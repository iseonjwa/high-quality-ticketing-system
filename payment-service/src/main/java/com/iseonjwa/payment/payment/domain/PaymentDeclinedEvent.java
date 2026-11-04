package com.iseonjwa.payment.payment.domain;

/**
 * SA 10-3 PaymentDeclined의 페이로드. {@code reasonCode}는 우리 DeclineReason 값이다.
 */
public record PaymentDeclinedEvent(Long paymentId, Long reservationId, Long userId, String reasonCode) {}
