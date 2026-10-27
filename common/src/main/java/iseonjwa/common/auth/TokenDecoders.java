package iseonjwa.common.auth;

import iseonjwa.common.identity.Roles;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

// Gateway와 User 서비스가 같은 토큰 계약으로 검증하도록 한곳에 둔다.
public final class TokenDecoders {
    private static final int MIN_KEY_BYTES = 32;

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
                new JwtTimestampValidator(Duration.ZERO),
                new JwtIssuerValidator(issuer),
                claimsOf(type)));
        return decoder;
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
