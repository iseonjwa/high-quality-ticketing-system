package com.iseonjwa.payment.pg.application;

import java.math.BigDecimal;

final class PgAmounts {
    private PgAmounts() {}

    static void requirePositive(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("PG 금액은 0보다 커야 합니다");
        }
    }

    // 소수 자리 표기가 달라도(50000, 50000.0) 같은 금액으로 본다
    static boolean sameAmount(BigDecimal expected, BigDecimal actual) {
        return expected.compareTo(actual) == 0;
    }
}
