package com.iseonjwa.payment.payment.domain;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * SA 10-3 PaymentRefunded의 페이로드. {@code amount}는 환불 금액이고 결제의 PG 승인 금액과 같다(전액 환불).
 * {@code refundedAt}은 전이 시각이 아니라 PG가 환불한 시각이다.
 */
public record PaymentRefundedEvent(Long paymentId, Long reservationId, Long userId, BigDecimal amount,
        String currency, Instant refundedAt) {}
