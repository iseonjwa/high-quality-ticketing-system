package com.iseonjwa.payment.global;

import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * 결제 토큰 검증 공개키(ADR 0054). 키 문자열은 X.509 SubjectPublicKeyInfo DER의 Base64 한 줄이고 P-256 곡선이어야 한다.
 * 서명은 예매 서비스만 개인키로 하고 이 서비스는 검증만 한다.
 *
 * <p>Note: 오류 메시지에는 어느 조건이 틀렸는지만 쓰고 키 값은 넣지 않는다. 공개키는 비밀이 아니지만 시작 로그와 예외에
 * 설정값이 남는 습관을 만들지 않는다.
 */
public final class PaymentTokenKey {
    private final ECPublicKey publicKey;

    private PaymentTokenKey(ECPublicKey publicKey) {
        this.publicKey = publicKey;
    }

    // 조건에 어긋나면 IllegalStateException을 던져 서비스 기동을 실패시킨다(fail-fast).
    public static PaymentTokenKey from(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("PAYMENT_TOKEN_PUBLIC_KEY가 설정되지 않았습니다");
        }
        byte[] der;
        try {
            der = Base64.getDecoder().decode(value.trim());
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("PAYMENT_TOKEN_PUBLIC_KEY는 Base64 한 줄이어야 합니다");
        }
        ECPublicKey key;
        try {
            var parsed = KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(der));
            if (!(parsed instanceof ECPublicKey ec)) {
                throw new IllegalStateException("PAYMENT_TOKEN_PUBLIC_KEY는 EC 키여야 합니다");
            }
            key = ec;
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("PAYMENT_TOKEN_PUBLIC_KEY는 X.509 SubjectPublicKeyInfo DER 형식의 EC 공개키여야 합니다");
        }
        if (!isP256(key.getParams())) {
            throw new IllegalStateException("PAYMENT_TOKEN_PUBLIC_KEY는 P-256 곡선이어야 합니다");
        }
        return new PaymentTokenKey(key);
    }

    public ECPublicKey publicKey() {
        return publicKey;
    }

    private static boolean isP256(ECParameterSpec params) {
        try {
            var parameters = AlgorithmParameters.getInstance("EC");
            parameters.init(new ECGenParameterSpec("secp256r1"));
            var p256 = parameters.getParameterSpec(ECParameterSpec.class);
            return p256.getCurve().equals(params.getCurve()) && p256.getOrder().equals(params.getOrder())
                    && p256.getGenerator().equals(params.getGenerator()) && p256.getCofactor() == params.getCofactor();
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("P-256 곡선 정의를 읽지 못했습니다", exception);
        }
    }

    @Override
    public String toString() {
        return "PaymentTokenKey[ES256 public key]";
    }
}
