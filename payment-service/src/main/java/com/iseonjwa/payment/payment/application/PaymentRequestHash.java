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
        // 구분자는 필드 값에 나올 수 없는 줄바꿈이다
        var canonical = paymentToken + "\n" + amount + "\n" + METHOD_CARD;
        try {
            var digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
