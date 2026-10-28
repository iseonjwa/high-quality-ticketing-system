package com.iseonjwa.payment.payment.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 같은 멱등키에 다른 본문이 오는지 알아보는 요청 해시. 본문의 의미 있는 필드만 정해진 순서로 이어 SHA-256을 낸다.
 */
final class PaymentRequestHash {
    private static final String METHOD_CARD = "CARD";

    private PaymentRequestHash() {}

    static String of(String paymentToken, long amount) {
        // Note: 토큰 원문이 해시에 들어간다. 같은 키로 재발급된 다른 토큰을 보내면 다른 본문으로 보고 409다(SA의 "같은 본문").
        // 금액은 숫자, 수단은 고정값이라 이어 붙이는 경계가 모호하지 않다
        var canonical = paymentToken + "\n" + amount + "\n" + METHOD_CARD;
        try {
            var digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
