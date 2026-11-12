package com.iseonjwa.payment.global;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static com.iseonjwa.payment.support.PaymentTokenFixtures.P384_PUBLIC_KEY;
import static com.iseonjwa.payment.support.PaymentTokenFixtures.RSA_PUBLIC_KEY;
import static com.iseonjwa.payment.support.PaymentTokenFixtures.TEST_PUBLIC_KEY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaymentTokenKeyTest {
    @Test
    void 올바른_공개키를_읽는다() {
        var key = PaymentTokenKey.from(TEST_PUBLIC_KEY);

        assertNotNull(key.publicKey());
        assertEquals("EC", key.publicKey().getAlgorithm());
        assertEquals(256, key.publicKey().getParams().getOrder().bitLength());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void 키가_없으면_기동을_막는다(String value) {
        var exception = assertThrows(IllegalStateException.class, () -> PaymentTokenKey.from(value));
        assertEquals("PAYMENT_TOKEN_PUBLIC_KEY가 설정되지 않았습니다", exception.getMessage());
    }

    @Test
    void Base64가_아니거나_공개키_형식이_아니면_값을_노출하지_않고_기동을_막는다() {
        var notBase64 = "이것은-base64가-아니다";
        var notKey = "QUJDREVGRw==";

        var base64Error = assertThrows(IllegalStateException.class, () -> PaymentTokenKey.from(notBase64));
        var formatError = assertThrows(IllegalStateException.class, () -> PaymentTokenKey.from(notKey));

        assertFalse(base64Error.getMessage().contains(notBase64));
        assertFalse(formatError.getMessage().contains(notKey));
    }

    @Test
    void P256이_아닌_곡선의_키는_기동을_막는다() {
        var exception = assertThrows(IllegalStateException.class, () -> PaymentTokenKey.from(P384_PUBLIC_KEY));

        assertTrue(exception.getMessage().contains("P-256"));
        assertFalse(exception.getMessage().contains(P384_PUBLIC_KEY));
    }

    @Test
    void EC가_아닌_키는_기동을_막는다() {
        assertThrows(IllegalStateException.class, () -> PaymentTokenKey.from(RSA_PUBLIC_KEY));
    }

    @Test
    void 개인키를_넣으면_공개키_형식이_아니라서_기동을_막는다() {
        assertThrows(IllegalStateException.class,
                () -> PaymentTokenKey.from(com.iseonjwa.payment.support.PaymentTokenFixtures.TEST_PRIVATE_KEY));
    }

    @Test
    void 문자열_표현에_키_값이_없다() {
        assertFalse(PaymentTokenKey.from(TEST_PUBLIC_KEY).toString().contains(TEST_PUBLIC_KEY));
    }
}
