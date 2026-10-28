package com.iseonjwa.payment.event.domain;

/**
 * SA 10-3 PaymentDeclined. {@code reasonCode}는 우리 DeclineReason 값이다.
 */
public record PaymentDeclinedEvent(Long paymentId, Long reservationId, Long userId, String reasonCode) {}
