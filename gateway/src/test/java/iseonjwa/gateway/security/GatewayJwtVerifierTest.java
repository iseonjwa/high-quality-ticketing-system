package iseonjwa.gateway.security;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GatewayJwtVerifierTest {
    private static final String SECRET = "test-access-secret-that-is-at-least-32-bytes-long";
    private final GatewayJwtVerifier verifier = new GatewayJwtVerifier(SECRET, "iseonjwa-auth");

    @Test
    void 정상_Access_토큰을_검증한다() {
        assertEquals("12", verifier.decode(token(SECRET, "iseonjwa-auth", "iseonjwa-api", "access", 1800)).getSubject());
    }

    @Test
    void 만료된_토큰을_거절한다() {
        assertThrows(JwtException.class, () -> verifier.decode(token(SECRET, "iseonjwa-auth", "iseonjwa-api", "access", -120)));
    }

    @Test
    void 다른_서명키를_거절한다() {
        assertThrows(JwtException.class, () -> verifier.decode(token(SECRET + "other", "iseonjwa-auth", "iseonjwa-api", "access", 1800)));
    }

    @Test
    void 다른_발급자와_대상을_거절한다() {
        assertThrows(JwtException.class, () -> verifier.decode(token(SECRET, "other", "iseonjwa-api", "access", 1800)));
        assertThrows(JwtException.class, () -> verifier.decode(token(SECRET, "iseonjwa-auth", "other", "access", 1800)));
    }

    @Test
    void 같은_키로_서명해도_Refresh_종류는_거절한다() {
        assertThrows(JwtException.class, () -> verifier.decode(token(SECRET, "iseonjwa-auth", "iseonjwa-api", "refresh", 1800)));
    }

    private String token(String key, String issuer, String audience, String kind, long lifetime) {
        var encoder = new NimbusJwtEncoder(new ImmutableSecret<>(key.getBytes(StandardCharsets.UTF_8)));
        var claims = JwtClaimsSet.builder().issuer(issuer).subject("12").audience(List.of(audience))
                .issuedAt(Instant.now().minusSeconds(180)).expiresAt(Instant.now().plusSeconds(lifetime))
                .id("test-jti").claim("role", "USER").claim("token_type", kind).build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }
}
