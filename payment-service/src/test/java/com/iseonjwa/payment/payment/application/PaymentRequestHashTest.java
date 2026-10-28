package com.iseonjwa.payment.payment.application;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class PaymentRequestHashTest {
    @Test
    void 같은_본문은_같은_64자_해시다() {
        var hash = PaymentRequestHash.of("token", 50000);

        assertEquals(hash, PaymentRequestHash.of("token", 50000));
        assertEquals(64, hash.length());
    }

    @Test
    void 토큰이나_금액이_다르면_해시가_다르다() {
        assertNotEquals(PaymentRequestHash.of("token", 50000), PaymentRequestHash.of("token2", 50000));
        assertNotEquals(PaymentRequestHash.of("token", 50000), PaymentRequestHash.of("token", 49000));
    }

    @Test
    void 필드_경계를_옮겨도_같은_해시가_되지_않는다() {
        assertNotEquals(PaymentRequestHash.of("token5", 0), PaymentRequestHash.of("token", 50));
    }
}
