package com.iseonjwa.payment.pg.application;

import java.math.BigDecimal;
import java.util.Objects;

public record RefundCommand(PgPaymentRef paymentRef, PgRefundId refundId, BigDecimal amount, String currency,
        String reason, PgIdempotencyKey idempotencyKey) {
    public RefundCommand {
        Objects.requireNonNull(paymentRef);
        Objects.requireNonNull(refundId);
        PgAmounts.requirePositive(amount);
        Objects.requireNonNull(currency);
        Objects.requireNonNull(reason);
        Objects.requireNonNull(idempotencyKey);
    }
}
