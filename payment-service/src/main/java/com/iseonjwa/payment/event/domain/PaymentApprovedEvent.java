package com.iseonjwa.payment.event.domain;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * SA 10-3 PaymentApproved. {@code currency}는 S-18로 추가한 필드라 version은 1 그대로다.
 */
public record PaymentApprovedEvent(Long paymentId, Long reservationId, Long userId, BigDecimal amount,
        String currency, Instant approvedAt) {}
