package com.iseonjwa.payment.payment.infrastructure.token;

import com.iseonjwa.common.response.BusinessException;
import com.iseonjwa.payment.global.PaymentTokenKey;
import com.iseonjwa.payment.payment.application.PaymentErrorCode;
import com.iseonjwa.payment.payment.application.PaymentToken;
import com.iseonjwa.payment.payment.application.PaymentTokenVerifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Component;

import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;

/**
 * HS256 결제 토큰 검증(0025). 알고리즘을 HS256으로 고정해 헤더의 {@code alg}를 믿지 않고, 서명·용도·대상·필수 claim을
 * 확인한다. 만료 검사는 일부러 빼서(타임스탬프 검증기를 쓰지 않는다) 호출한 쪽이 조회 전용 여부를 판단하게 한다.
 */
@Component
class JwtPaymentTokenVerifier implements PaymentTokenVerifier {
    private static final Logger log = LoggerFactory.getLogger(JwtPaymentTokenVerifier.class);
    private final JwtDecoder decoder;

    JwtPaymentTokenVerifier(PaymentTokenKey key) {
        var nimbus = NimbusJwtDecoder.withSecretKey(new SecretKeySpec(key.bytes(), "HmacSHA256"))
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        nimbus.setJwtValidator(new DelegatingOAuth2TokenValidator<>(claims()));
        this.decoder = nimbus;
    }

    @Override
    public PaymentToken verify(String token) {
        if (token == null || token.length() > MAX_TOKEN_LENGTH) {
            throw invalid();
        }
        try {
            var jwt = decoder.decode(token);
            return new PaymentToken(Long.parseLong(jwt.getSubject()), reservationId(jwt), amount(jwt),
                    jwt.getExpiresAt());
        } catch (RuntimeException exception) {
            // 어떤 사유로 틀렸는지는 호출자에게 알리지 않고 서버 로그에만 남긴다. 토큰 값은 남기지 않는다
            log.warn("결제 토큰 검증 실패 reason={}", exception.getClass().getSimpleName());
            throw invalid();
        }
    }

    // 용도(typ)·대상(aud)·필수 claim. exp는 존재만 확인하고 만료는 보지 않는다
    private static OAuth2TokenValidator<Jwt> claims() {
        return jwt -> isWellFormed(jwt) ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"));
    }

    private static boolean isWellFormed(Jwt jwt) {
        try {
            return Long.parseLong(jwt.getSubject()) > 0
                    && jwt.getId() != null
                    && jwt.getExpiresAt() != null
                    && jwt.getAudience().contains(PaymentTokenClaims.AUDIENCE_VALUE)
                    && PaymentTokenClaims.TYPE_VALUE.equals(jwt.getClaimAsString(PaymentTokenClaims.TYPE));
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private static long reservationId(Jwt jwt) {
        var value = jwt.getClaim(PaymentTokenClaims.RESERVATION_ID);
        // 예매 ID는 정수 claim이어야 한다. 소수는 longValue()로 잘려 다른 예매가 되므로 받지 않는다
        if (!(value instanceof Long || value instanceof Integer) || ((Number) value).longValue() <= 0) {
            throw new IllegalArgumentException();
        }
        return ((Number) value).longValue();
    }

    // KRW는 정수 금액이다. 소수가 섞이면 토큰이 잘못된 것이다
    private static BigDecimal amount(Jwt jwt) {
        var value = jwt.getClaim(PaymentTokenClaims.AMOUNT);
        if (!(value instanceof Number number)) throw new IllegalArgumentException();
        var amount = new BigDecimal(number.toString());
        if (amount.signum() <= 0 || amount.stripTrailingZeros().scale() > 0) throw new IllegalArgumentException();
        return amount.setScale(0);
    }

    private static BusinessException invalid() {
        return new BusinessException(PaymentErrorCode.PAYMENT_TOKEN_INVALID);
    }
}
