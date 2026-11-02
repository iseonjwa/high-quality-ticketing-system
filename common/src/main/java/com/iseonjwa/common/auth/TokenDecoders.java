package com.iseonjwa.common.auth;

import com.iseonjwa.common.identity.Roles;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;

// Gateway와 User 서비스가 같은 토큰 계약으로 검증하도록 한곳에 둔다.
public final class TokenDecoders {
    private static final int MIN_KEY_BYTES = 32;
    // 만료 실패에만 붙이는 오류 코드. 다른 검증 실패와 나눠 401 TOKEN_EXPIRED로 응답할 수 있게 한다(SA 11-3).
    private static final String EXPIRED_ERROR_CODE = "token_expired";

    private TokenDecoders() {}

    public static byte[] signingKey(String secret) {
        var key = secret.getBytes(StandardCharsets.UTF_8);
        if (key.length < MIN_KEY_BYTES) {
            throw new IllegalArgumentException("서명 키는 32바이트 이상이어야 합니다");
        }
        return key;
    }

    public static NimbusJwtDecoder create(String secret, String issuer, TokenType type) {
        var decoder = NimbusJwtDecoder
                .withSecretKey(new SecretKeySpec(signingKey(secret), "HmacSHA256"))
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                timestamps(),
                new JwtIssuerValidator(issuer),
                claimsOf(type)));
        return decoder;
    }

    // Note: NimbusJwtDecoder는 서명을 먼저 검증하고, 통과한 토큰에만 이 검증기들을 돌린다.
    // 그래서 만료로 판정되는 토큰은 서명이 맞는 토큰뿐이고, 위조 토큰에는 만료라고 알리지 않는다.
    public static boolean isExpired(JwtException exception) {
        return exception instanceof JwtValidationException validation
                && validation.getErrors().stream()
                        .anyMatch(error -> EXPIRED_ERROR_CODE.equals(error.getErrorCode()));
    }

    // JwtTimestampValidator는 만료(exp)와 사용 시작(nbf) 실패를 같은 invalid_token으로 준다.
    // 만료 시각이 지났을 때만 고유 코드로 바꾸고, 그 밖의 실패는 그대로 둔다.
    private static OAuth2TokenValidator<Jwt> timestamps() {
        var delegate = new JwtTimestampValidator(Duration.ZERO);
        return jwt -> {
            var result = delegate.validate(jwt);
            if (!result.hasErrors() || !isPastExpiry(jwt)) {
                return result;
            }
            return OAuth2TokenValidatorResult.failure(new OAuth2Error(EXPIRED_ERROR_CODE, "토큰이 만료됐습니다", null));
        };
    }

    private static boolean isPastExpiry(Jwt jwt) {
        var expiresAt = jwt.getExpiresAt();
        return expiresAt != null && Instant.now().isAfter(expiresAt);
    }

    private static OAuth2TokenValidator<Jwt> claimsOf(TokenType type) {
        return jwt -> isValid(jwt, type)
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"));
    }

    private static boolean isValid(Jwt jwt, TokenType type) {
        try {
            return Long.parseLong(jwt.getSubject()) > 0
                    && jwt.getId() != null
                    && jwt.getExpiresAt() != null
                    && jwt.getIssuedAt() != null
                    && jwt.getAudience().contains(type.audience())
                    && type.type().equals(jwt.getClaimAsString(TokenType.TYPE_CLAIM))
                    && Roles.isValid(jwt.getClaimAsString(TokenType.ROLE_CLAIM));
        } catch (RuntimeException exception) {
            return false;
        }
    }
}
