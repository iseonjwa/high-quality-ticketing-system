package com.iseonjwa.payment.pg.application;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * @param clientPaymentRef 구매자가 결제창에서 받은 PG 발급 식별자. 토스는 필수, Mock은 무시한다.
 */
public record ApproveCommand(PgOrderId orderId, BigDecimal amount, String currency, PgIdempotencyKey idempotencyKey,
        PgPaymentRef clientPaymentRef) {
    public ApproveCommand {
        Objects.requireNonNull(orderId);
        Objects.requireNonNull(idempotencyKey);
        PgAmounts.requirePositive(amount);
        Objects.requireNonNull(currency);
    }
}
