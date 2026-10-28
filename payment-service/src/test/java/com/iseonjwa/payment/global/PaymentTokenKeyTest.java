package com.iseonjwa.payment.global;

import com.iseonjwa.common.auth.TokenDecoders;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PaymentTokenKeyTest {
    private static final String VALID = "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff";

    @Test
    void 키_바이트는_발급기와_같은_공통_함수로_만든다() {
        var key = PaymentTokenKey.from(VALID);

        assertArrayEquals(TokenDecoders.signingKey(VALID), key.bytes());
        assertEquals(64, key.bytes().length, "16진수로 디코딩하지 않고 UTF-8 바이트 64개를 그대로 쓴다");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void 키가_없으면_기동을_막는다(String secret) {
        var exception = assertThrows(IllegalStateException.class, () -> PaymentTokenKey.from(secret));
        assertEquals("PAYMENT_TOKEN_SECRET이 설정되지 않았습니다", exception.getMessage());
    }

    @Test
    void 길이가_64자가_아니면_기동을_막는다() {
        assertThrows(IllegalStateException.class, () -> PaymentTokenKey.from(VALID.substring(1)));
        assertThrows(IllegalStateException.class, () -> PaymentTokenKey.from(VALID + "0"));
    }

    @Test
    void 대문자나_16진수가_아닌_문자가_있으면_기동을_막는다() {
        assertThrows(IllegalStateException.class, () -> PaymentTokenKey.from(VALID.toUpperCase()));
        assertThrows(IllegalStateException.class, () -> PaymentTokenKey.from("g" + VALID.substring(1)));
    }

    @Test
    void 오류_메시지와_문자열_표현에_키_값이_없다() {
        var badKey = "g" + VALID.substring(1);

        var exception = assertThrows(IllegalStateException.class, () -> PaymentTokenKey.from(badKey));

        assertFalse(exception.getMessage().contains(badKey));
        assertFalse(PaymentTokenKey.from(VALID).toString().contains(VALID));
    }

    @Test
    void 반환한_바이트를_바꿔도_키는_변하지_않는다() {
        var key = PaymentTokenKey.from(VALID);

        key.bytes()[0] = 0;

        assertArrayEquals(TokenDecoders.signingKey(VALID), key.bytes());
    }
}
