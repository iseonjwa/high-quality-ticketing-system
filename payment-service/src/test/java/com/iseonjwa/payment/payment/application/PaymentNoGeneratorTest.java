package com.iseonjwa.payment.payment.application;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaymentNoGeneratorTest {
    private final PaymentNoGenerator generator = new PaymentNoGenerator(
            Clock.fixed(Instant.parse("2026-11-02T00:00:00Z"), ZoneOffset.UTC));

    @Test
    void 번호는_PAY_날짜_영숫자_8자이고_컬럼_길이_안이다() {
        var number = generator.next();

        assertTrue(number.matches("PAY261102[0-9A-Z]{8}"), number);
        assertTrue(number.length() <= 20);
    }

    @Test
    void 연속으로_만들어도_겹치지_않는다() {
        var numbers = new HashSet<String>();
        for (var i = 0; i < 1000; i++) numbers.add(generator.next());

        assertEquals(1000, numbers.size());
    }
}
