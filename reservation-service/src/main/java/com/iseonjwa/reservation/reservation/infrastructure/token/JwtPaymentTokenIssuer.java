package com.iseonjwa.reservation.reservation.infrastructure.token;

import com.iseonjwa.reservation.reservation.application.PaymentTokenIssuer;
import com.iseonjwa.reservation.reservation.domain.entity.Reservation;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.interfaces.ECPrivateKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;

/**
 * ES256 결제 토큰 발급(ADR 0055). 개인키는 이 서비스만 갖고 결제 서비스는 공개키로 검증한다. claim 이름과 값은 Payment의
 * {@code PaymentTokenClaims}·{@code JwtPaymentTokenVerifier}와 맞춘다. 한쪽을 바꾸면 결제 승인이 모두 403
 * {@code PAYMENT_TOKEN_INVALID}가 된다.
 */
@Component
public class JwtPaymentTokenIssuer implements PaymentTokenIssuer {
    static final String TYPE = "typ";
    static final String TYPE_VALUE = "payment";
    static final String AUDIENCE_VALUE = "payment-service";
    static final String RESERVATION_ID = "reservationId";
    static final String AMOUNT = "amount";

    private final ECDSASigner signer;
    private final Clock clock;

    // Note: 키 값이 없거나 형식이 틀리면 기동을 막는다. 메시지에는 어느 조건이 틀렸는지만 쓰고 키 값은 넣지 않는다
    public JwtPaymentTokenIssuer(@Value("${payment.token.private-key:}") String privateKey,
            @Qualifier("commonAuditingClock") Clock clock) {
        this.signer = signer(parse(privateKey));
        this.clock = clock;
    }

    @Override
    public IssuedPaymentToken issue(Long userId, Long reservationId, long amount, Instant holdExpiresAt) {
        var expiresAt = holdExpiresAt.minus(Reservation.PAYMENT_CLOSE_MARGIN);
        var claims = new JWTClaimsSet.Builder()
                .subject(userId.toString())
                .audience(AUDIENCE_VALUE)
                .issueTime(Date.from(clock.instant()))
                .expirationTime(Date.from(expiresAt))
                .jwtID(UUID.randomUUID().toString())
                .claim(TYPE, TYPE_VALUE)
                .claim(RESERVATION_ID, reservationId)
                .claim(AMOUNT, amount)
                .build();
        var header = new JWSHeader.Builder(JWSAlgorithm.ES256).type(JOSEObjectType.JWT).build();
        var jwt = new SignedJWT(header, claims);
        try {
            jwt.sign(signer);
        } catch (JOSEException exception) {
            throw new IllegalStateException("결제 토큰 서명에 실패했습니다", exception);
        }
        return new IssuedPaymentToken(jwt.serialize(), expiresAt);
    }

    private static ECPrivateKey parse(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("PAYMENT_TOKEN_PRIVATE_KEY가 설정되지 않았습니다");
        }
        byte[] der;
        try {
            der = Base64.getDecoder().decode(value.trim());
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("PAYMENT_TOKEN_PRIVATE_KEY는 Base64 한 줄이어야 합니다");
        }
        ECPrivateKey key;
        try {
            var parsed = KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(der));
            if (!(parsed instanceof ECPrivateKey ec)) {
                throw new IllegalStateException("PAYMENT_TOKEN_PRIVATE_KEY는 EC 키여야 합니다");
            }
            key = ec;
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("PAYMENT_TOKEN_PRIVATE_KEY는 PKCS#8 DER 형식이어야 합니다");
        }
        if (!isP256(key.getParams())) {
            throw new IllegalStateException("PAYMENT_TOKEN_PRIVATE_KEY는 P-256 곡선이어야 합니다");
        }
        return key;
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

    private static ECDSASigner signer(ECPrivateKey key) {
        try {
            return new ECDSASigner(key);
        } catch (JOSEException exception) {
            throw new IllegalStateException("PAYMENT_TOKEN_PRIVATE_KEY로 서명기를 만들지 못했습니다");
        }
    }
}
