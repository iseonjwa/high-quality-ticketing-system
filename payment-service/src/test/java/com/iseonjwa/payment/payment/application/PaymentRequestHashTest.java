package com.iseonjwa.payment.payment.application;

import com.iseonjwa.payment.payment.domain.PgProvider;
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

    @Test
    void clientPaymentRef가_없으면_이전_형식과_같은_해시라_저장된_멱등_기록과_호환된다() {
        // 이 값은 clientPaymentRef가 생기기 전 구현이 만든 해시다(token\n50000\nCARD의 SHA-256)
        assertEquals("21e5b0d75e1bfb5c50151b05ac2e3f5cb86ff92db17482aa7adf97b66a7f91a3",
                PaymentRequestHash.of("token", 50000, null));
        assertEquals(PaymentRequestHash.of("token", 50000), PaymentRequestHash.of("token", 50000, null));
    }

    @Test
    void clientPaymentRef가_다르면_해시가_다르다() {
        assertNotEquals(PaymentRequestHash.of("token", 50000, null), PaymentRequestHash.of("token", 50000, "key-a"));
        assertNotEquals(PaymentRequestHash.of("token", 50000, "key-a"), PaymentRequestHash.of("token", 50000, "key-b"));
    }

    @Test
    void pgProvider가_없으면_이전_형식과_같은_해시다() {
        assertEquals(PaymentRequestHash.of("token", 50000, "key-a"), PaymentRequestHash.of("token", 50000, "key-a", null));
        assertEquals("21e5b0d75e1bfb5c50151b05ac2e3f5cb86ff92db17482aa7adf97b66a7f91a3",
                PaymentRequestHash.of("token", 50000, null, null));
    }

    @Test
    void pgProvider_유무와_값이_다르면_해시가_다르다() {
        assertNotEquals(PaymentRequestHash.of("token", 50000, null), PaymentRequestHash.of("token", 50000, null, PgProvider.MOCK));
        assertNotEquals(PaymentRequestHash.of("token", 50000, null, PgProvider.MOCK),
                PaymentRequestHash.of("token", 50000, null, PgProvider.TOSS));
        assertNotEquals(PaymentRequestHash.of("token", 50000, "key-a", PgProvider.TOSS),
                PaymentRequestHash.of("token", 50000, "key-b", PgProvider.TOSS));
    }

    @Test
    void ref가_이전_형식의_다음_줄처럼_보이는_값이어도_pgProvider_형식과_겹치지_않는다() {
        // 공백 없는 clientPaymentRef는 줄바꿈을 담을 수 없어 "ref=..." 줄을 흉내 낼 수 없다
        assertNotEquals(PaymentRequestHash.of("token", 50000, "ref=x"), PaymentRequestHash.of("token", 50000, "x", PgProvider.MOCK));
    }
}
