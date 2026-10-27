package iseonjwa.user.infrastructure.security;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import iseonjwa.user.domain.AuthTokens;
import iseonjwa.common.response.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Component;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;

@Component
public class JwtTokens implements AuthTokens {
    private final JwtEncoder accessEncoder;
    private final JwtEncoder refreshEncoder;
    private final NimbusJwtDecoder accessDecoder;
    private final NimbusJwtDecoder refreshDecoder;
    private final String issuer;
    private final long accessSeconds;
    private final long refreshSeconds;

    public JwtTokens(@Value("${auth.access-secret}") String accessSecret,
                     @Value("${auth.refresh-secret}") String refreshSecret,
                     @Value("${auth.issuer}") String issuer,
                     @Value("${auth.access-seconds}") long accessSeconds,
                     @Value("${auth.refresh-seconds}") long refreshSeconds) {
        if (accessSecret.equals(refreshSecret)) throw new IllegalArgumentException("토큰 서명 키는 분리해야 합니다");
        this.issuer = issuer;
        this.accessSeconds = accessSeconds;
        this.refreshSeconds = refreshSeconds;
        accessEncoder = new NimbusJwtEncoder(new ImmutableSecret<>(key(accessSecret)));
        refreshEncoder = new NimbusJwtEncoder(new ImmutableSecret<>(key(refreshSecret)));
        accessDecoder = decoder(accessSecret, "access", "iseonjwa-api");
        refreshDecoder = decoder(refreshSecret, "refresh", "user-service");
    }

    public Pair issue(Long userId, String role) {
        return new Pair(encode(accessEncoder, userId, role, "access", "iseonjwa-api", accessSeconds),
                encode(refreshEncoder, userId, role, "refresh", "user-service", refreshSeconds), accessSeconds);
    }

    public Identity decodeAccess(String token) { return identity(decode(accessDecoder, token)); }
    public Identity decodeRefresh(String token) { return identity(decode(refreshDecoder, token)); }
    public Duration refreshTtl() { return Duration.ofSeconds(refreshSeconds); }

    private Identity identity(Jwt jwt) { return new Identity(Long.parseLong(jwt.getSubject()), jwt.getId(), jwt.getExpiresAt()); }

    private Jwt decode(JwtDecoder decoder, String token) {
        try { return decoder.decode(token); }
        catch (JwtException | IllegalArgumentException exception) {
            throw new BusinessException(CommonErrorCode.UNAUTHORIZED);
        }
    }

    private String encode(JwtEncoder encoder, Long userId, String role, String kind, String audience, long ttl) {
        var now = Instant.now();
        var claims = JwtClaimsSet.builder().issuer(issuer).subject(userId.toString()).audience(List.of(audience))
                .issuedAt(now).expiresAt(now.plusSeconds(ttl)).id(UUID.randomUUID().toString())
                .claim("token_type", kind).claim("role", role).build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).type("JWT").build(), claims)).getTokenValue();
    }

    private NimbusJwtDecoder decoder(String secret, String kind, String audience) {
        var decoder = NimbusJwtDecoder.withSecretKey(new SecretKeySpec(key(secret), "HmacSHA256"))
                .macAlgorithm(MacAlgorithm.HS256).build();
        OAuth2TokenValidator<Jwt> contract = jwt -> {
            boolean valid;
            try {
                valid = Long.parseLong(jwt.getSubject()) > 0 && jwt.getId() != null
                        && jwt.getExpiresAt() != null && jwt.getIssuedAt() != null
                        && jwt.getAudience().contains(audience)
                        && kind.equals(jwt.getClaimAsString("token_type"))
                        && List.of("USER", "ADMIN").contains(jwt.getClaimAsString("role"));
            } catch (RuntimeException exception) { valid = false; }
            return valid ? OAuth2TokenValidatorResult.success()
                    : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"));
        };
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(new JwtTimestampValidator(Duration.ZERO),
                new JwtIssuerValidator(issuer), contract));
        return decoder;
    }

    private byte[] key(String secret) {
        var bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) throw new IllegalArgumentException("서명 키는 32바이트 이상이어야 합니다");
        return bytes;
    }
}
