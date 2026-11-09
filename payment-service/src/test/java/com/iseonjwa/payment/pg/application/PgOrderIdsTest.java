package com.iseonjwa.payment.pg.application;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PgOrderIdsTest {
    private static final Instant EXP = Instant.parse("2026-11-09T03:00:00Z");

    @Test
    void 같은_예매와_토큰_만료_시각은_언제_불러도_같은_주문_식별자다() {
        assertEquals(PgOrderIds.of(100, EXP), PgOrderIds.of(100, EXP));
    }

    @Test
    void 초_아래_시각은_토큰의_exp_정밀도에_맞춰_무시한다() {
        assertEquals(PgOrderIds.of(100, EXP), PgOrderIds.of(100, EXP.plusMillis(999)));
    }

    @Test
    void 예매나_만료_시각이_다르면_다른_주문_식별자다() {
        assertNotEquals(PgOrderIds.of(100, EXP), PgOrderIds.of(101, EXP));
        assertNotEquals(PgOrderIds.of(100, EXP), PgOrderIds.of(100, EXP.plusSeconds(1)));
    }

    @Test
    void 토스_orderId_규칙인_6에서_64자의_영문_숫자_하이픈이다() {
        var text = PgOrderIds.of(100, EXP).value().toString();

        assertTrue(text.length() >= 6 && text.length() <= 64);
        assertTrue(text.matches("[A-Za-z0-9_=-]+"));
    }
}
