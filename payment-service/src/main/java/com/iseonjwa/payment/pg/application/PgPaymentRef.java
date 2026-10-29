package com.iseonjwa.payment.pg.application;

/**
 * PG가 발급한 불투명 거래 식별자(`payments.pg_tx_id`). 서비스 코드는 값의 의미를 해석하지 않는다.
 */
public record PgPaymentRef(String value) {
    public PgPaymentRef {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("PG 거래 식별자가 비어 있습니다");
        }
    }
}
