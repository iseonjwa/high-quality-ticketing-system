package com.iseonjwa.payment.pg.application;

import java.util.Objects;
import java.util.UUID;

/**
 * 우리가 발급하는 환불 식별자(`refund.pg_refund_id`). PG 환불 멱등키의 원천이다.
 */
public record PgRefundId(UUID value) {
    public PgRefundId {
        Objects.requireNonNull(value);
    }
}
