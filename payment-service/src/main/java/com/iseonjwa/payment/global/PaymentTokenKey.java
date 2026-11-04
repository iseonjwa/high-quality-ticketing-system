package com.iseonjwa.payment.global;

import com.iseonjwa.common.auth.TokenDecoders;

import java.util.regex.Pattern;

/**
 * 결제 토큰 서명 키(ADR 0025). 키 문자열은 소문자 16진수 64자이고, HMAC 키 바이트는 발급기와 같은
 * {@link TokenDecoders#signingKey(String)}로 만든다. 16진수로 디코딩하면 같은 문자열에서 발급기와 다른 바이트가 나온다.
 *
 * <p>Note: 오류 메시지에는 어느 조건이 틀렸는지만 쓰고 키 값은 넣지 않는다. 시작 로그와 예외에 키가 남으면 안 된다.
 */
public final class PaymentTokenKey {
    static final int SECRET_LENGTH = 64;
    private static final Pattern LOWERCASE_HEX = Pattern.compile("[0-9a-f]+");

    private final byte[] bytes;

    private PaymentTokenKey(byte[] bytes) {
        this.bytes = bytes;
    }

    // 조건에 어긋나면 IllegalStateException을 던져 서비스 기동을 실패시킨다(fail-fast).
    public static PaymentTokenKey from(String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("PAYMENT_TOKEN_SECRET이 설정되지 않았습니다");
        }
        if (secret.length() != SECRET_LENGTH) {
            throw new IllegalStateException("PAYMENT_TOKEN_SECRET은 " + SECRET_LENGTH + "자여야 합니다");
        }
        if (!LOWERCASE_HEX.matcher(secret).matches()) {
            throw new IllegalStateException("PAYMENT_TOKEN_SECRET은 소문자 16진수여야 합니다");
        }
        return new PaymentTokenKey(TokenDecoders.signingKey(secret));
    }

    public byte[] bytes() {
        return bytes.clone();
    }

    @Override
    public String toString() {
        return "PaymentTokenKey[REDACTED]";
    }
}
