package com.iseonjwa.payment.pg.application;

import java.util.Objects;
import java.util.UUID;

/**
 * 우리가 발급하는 PG 주문 식별자(`payment.pg_order_id`). PG 호출 전에 저장해 멱등키의 원천으로 쓴다.
 */
public record PgOrderId(UUID value) {
    public PgOrderId {
        Objects.requireNonNull(value);
    }
}
