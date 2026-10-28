package com.iseonjwa.payment.payment.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PaymentTest {
    private static final Instant NOW = Instant.parse("2026-10-28T03:00:00Z");

    @Test
    void 새_결제는_REQUESTED이고_10초_뒤에_재확인한다() {
        var payment = Payment.request("PAY-1", 10L, 1L, BigDecimal.valueOf(50000), PaymentMethod.CARD,
                UUID.randomUUID(), NOW);

        assertEquals(PaymentStatus.REQUESTED, payment.getStatus());
        assertEquals("KRW", payment.getCurrency());
        assertEquals(NOW.plusSeconds(10), payment.getNextCheckAt());
    }

    @Test
    void 금액이_0_이하이면_만들지_못한다() {
        assertThrows(IllegalArgumentException.class, () -> Payment.request("PAY-1", 10L, 1L, BigDecimal.ZERO,
                PaymentMethod.CARD, UUID.randomUUID(), NOW));
    }
}
