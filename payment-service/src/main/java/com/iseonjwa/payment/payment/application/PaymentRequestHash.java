package com.iseonjwa.payment.payment.application;

import com.iseonjwa.payment.payment.domain.PgProvider;

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
        return of(paymentToken, amount, null);
    }

    static String of(String paymentToken, long amount, String clientPaymentRef) {
        return of(paymentToken, amount, clientPaymentRef, null);
    }

    static String of(String paymentToken, long amount, String clientPaymentRef, PgProvider pgProvider) {
        // Note: 토큰 원문이 해시에 들어간다. 같은 키로 재발급된 다른 토큰을 보내면 다른 본문으로 보고 409다(SA의 "같은 본문").
        // 금액은 숫자, 수단은 고정값이라 이어 붙이는 경계가 모호하지 않다.
        // clientPaymentRef가 없으면 이전 형식 그대로라 이 필드가 생기기 전에 저장된 멱등 기록과 해시가 같다
        // pgProvider가 있으면 ref와 pg 줄을 붙인다. clientPaymentRef는 공백 없는 문자열(\S)이라 줄바꿈을 담을 수 없으므로
        // "\nref=" 이후의 형식이 pgProvider 없는 형식("\n" + clientPaymentRef)과 겹치지 않는다. 같은 키에 pgProvider 유무가
        // 달라지면 다른 본문으로 보고 409다
        var canonical = paymentToken + "\n" + amount + "\n" + METHOD_CARD
                + (pgProvider == null
                        ? (clientPaymentRef == null ? "" : "\n" + clientPaymentRef)
                        : "\nref=" + (clientPaymentRef == null ? "" : clientPaymentRef) + "\npg=" + pgProvider.name());
        try {
            var digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
