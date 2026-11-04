package com.iseonjwa.payment.payment.domain;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * SA 10-3 PaymentApproved의 페이로드. {@code currency}는 S-18로 추가한 필드라 version은 1 그대로다.
 * {@code approvedAt}은 전이 시각이 아니라 PG가 승인한 시각이다(0027: 만료 판단 기준).
 */
public record PaymentApprovedEvent(Long paymentId, Long reservationId, Long userId, BigDecimal amount,
        String currency, Instant approvedAt) {}
