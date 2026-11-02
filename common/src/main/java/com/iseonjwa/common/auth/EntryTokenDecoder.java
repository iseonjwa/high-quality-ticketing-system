package com.iseonjwa.common.auth;

import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import javax.crypto.spec.SecretKeySpec;
import java.time.Duration;

public class EntryTokenDecoder {

    private static final int MAX_LENGTH = 4096;
    private final NimbusJwtDecoder nimbusJwtDecoder;


    public EntryTokenDecoder(String secret) {
        this.nimbusJwtDecoder = NimbusJwtDecoder
                .withSecretKey(new SecretKeySpec(TokenDecoders.signingKey(secret), "HmacSHA256"))
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        this.nimbusJwtDecoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                new JwtTimestampValidator(Duration.ZERO),
                jwt -> isValid(jwt)
                        ? OAuth2TokenValidatorResult.success()
                        : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"))));
    }

    public Jwt decode(String tokenValue) {
        if (tokenValue == null || tokenValue.length() > MAX_LENGTH) {
            throw new BadJwtException("invalid entry token");
        }
        return nimbusJwtDecoder.decode(tokenValue);
    }

    private boolean isValid(Jwt jwt) {
        try {
            return EntryTokenClaims.TYPE_VALUE.equals(jwt.getClaimAsString(EntryTokenClaims.TYPE))
                    && jwt.getAudience().contains(EntryTokenClaims.AUDIENCE_VALUE)
                    && Long.parseLong(jwt.getSubject()) > 0
                    && jwt.getClaim(EntryTokenClaims.SCHEDULE_ID) instanceof Long
                    && jwt.getExpiresAt() != null
                    && jwt.getIssuedAt() != null
                    && jwt.getId() != null;
        } catch (RuntimeException e) {
            return false; // parseLong()에서 터지는 예외
        }
    }
}
