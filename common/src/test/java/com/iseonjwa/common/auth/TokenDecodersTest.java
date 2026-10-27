package com.iseonjwa.common.auth;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TokenDecodersTest {
    private static final String SECRET = "test-access-secret-that-is-at-least-32-bytes-long";
    private static final String ISSUER = "iseonjwa-auth";
    private final NimbusJwtDecoder accessDecoder = TokenDecoders.create(SECRET, ISSUER, TokenType.ACCESS);

    @Test
    void 정상_Access_토큰을_검증한다() {
        var token = token(SECRET, ISSUER, "iseonjwa-api", "access", "USER", 1800);

        assertEquals("12", accessDecoder.decode(token).getSubject());
    }

    @Test
    void 만료된_토큰을_거절한다() {
        var token = token(SECRET, ISSUER, "iseonjwa-api", "access", "USER", -120);

        assertThrows(JwtException.class, () -> accessDecoder.decode(token));
    }

    @Test
    void 다른_서명키를_거절한다() {
        var token = token(SECRET + "other", ISSUER, "iseonjwa-api", "access", "USER", 1800);

        assertThrows(JwtException.class, () -> accessDecoder.decode(token));
    }

    @Test
    void 다른_발급자와_대상을_거절한다() {
        var otherIssuer = token(SECRET, "other", "iseonjwa-api", "access", "USER", 1800);
        var otherAudience = token(SECRET, ISSUER, "other", "access", "USER", 1800);

        assertThrows(JwtException.class, () -> accessDecoder.decode(otherIssuer));
        assertThrows(JwtException.class, () -> accessDecoder.decode(otherAudience));
    }

    @Test
    void 같은_키로_서명해도_Refresh_종류는_거절한다() {
        var token = token(SECRET, ISSUER, "iseonjwa-api", "refresh", "USER", 1800);

        assertThrows(JwtException.class, () -> accessDecoder.decode(token));
    }

    @Test
    void 알_수_없는_역할을_거절한다() {
        var token = token(SECRET, ISSUER, "iseonjwa-api", "access", "SUPER", 1800);

        assertThrows(JwtException.class, () -> accessDecoder.decode(token));
    }

    @Test
    void Refresh_디코더는_Refresh_토큰만_받는다() {
        var refreshDecoder = TokenDecoders.create(SECRET, ISSUER, TokenType.REFRESH);
        var refresh = token(SECRET, ISSUER, "user-service", "refresh", "USER", 1800);
        var access = token(SECRET, ISSUER, "iseonjwa-api", "access", "USER", 1800);

        assertEquals("12", refreshDecoder.decode(refresh).getSubject());
        assertThrows(JwtException.class, () -> refreshDecoder.decode(access));
    }

    @Test
    void 짧은_서명키는_거절한다() {
        assertThrows(IllegalArgumentException.class, () -> TokenDecoders.signingKey("short"));
    }

    private String token(String key, String issuer, String audience, String type, String role, long lifetime) {
        var encoder = new NimbusJwtEncoder(new ImmutableSecret<>(key.getBytes(StandardCharsets.UTF_8)));
        var claims = JwtClaimsSet.builder()
                .issuer(issuer)
                .subject("12")
                .audience(List.of(audience))
                .issuedAt(Instant.now().minusSeconds(180))
                .expiresAt(Instant.now().plusSeconds(lifetime))
                .id("test-jti")
                .claim(TokenType.ROLE_CLAIM, role)
                .claim(TokenType.TYPE_CLAIM, type)
                .build();
        var header = JwsHeader.with(MacAlgorithm.HS256).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }
}
