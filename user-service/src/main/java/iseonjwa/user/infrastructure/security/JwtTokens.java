package iseonjwa.user.infrastructure.security;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import iseonjwa.common.auth.TokenDecoders;
import iseonjwa.common.auth.TokenType;
import iseonjwa.common.response.BusinessException;
import iseonjwa.common.response.CommonErrorCode;
import iseonjwa.user.domain.AuthTokens;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Component
public class JwtTokens implements AuthTokens {
    private final JwtEncoder accessEncoder;
    private final JwtEncoder refreshEncoder;
    private final JwtDecoder accessDecoder;
    private final JwtDecoder refreshDecoder;
    private final String issuer;
    private final long accessSeconds;
    private final long refreshSeconds;

    public JwtTokens(@Value("${auth.access-secret}") String accessSecret,
            @Value("${auth.refresh-secret}") String refreshSecret,
            @Value("${auth.issuer}") String issuer,
            @Value("${auth.access-seconds}") long accessSeconds,
            @Value("${auth.refresh-seconds}") long refreshSeconds) {
        if (accessSecret.equals(refreshSecret)) {
            throw new IllegalArgumentException("토큰 서명 키는 분리해야 합니다");
        }
        this.issuer = issuer;
        this.accessSeconds = accessSeconds;
        this.refreshSeconds = refreshSeconds;
        this.accessEncoder = encoder(accessSecret);
        this.refreshEncoder = encoder(refreshSecret);
        this.accessDecoder = TokenDecoders.create(accessSecret, issuer, TokenType.ACCESS);
        this.refreshDecoder = TokenDecoders.create(refreshSecret, issuer, TokenType.REFRESH);
    }

    @Override
    public Pair issue(Long userId, String role) {
        var access = encode(accessEncoder, TokenType.ACCESS, userId, role, accessSeconds);
        var refresh = encode(refreshEncoder, TokenType.REFRESH, userId, role, refreshSeconds);
        return new Pair(access, refresh, accessSeconds);
    }

    @Override
    public Identity decodeAccess(String token) {
        return identity(decode(accessDecoder, token));
    }

    @Override
    public Identity decodeRefresh(String token) {
        return identity(decode(refreshDecoder, token));
    }

    @Override
    public Duration refreshTtl() {
        return Duration.ofSeconds(refreshSeconds);
    }

    private Identity identity(Jwt jwt) {
        return new Identity(Long.parseLong(jwt.getSubject()), jwt.getId(), jwt.getExpiresAt());
    }

    private Jwt decode(JwtDecoder decoder, String token) {
        try {
            return decoder.decode(token);
        } catch (JwtException | IllegalArgumentException exception) {
            throw new BusinessException(CommonErrorCode.UNAUTHORIZED);
        }
    }

    private String encode(JwtEncoder encoder, TokenType type, Long userId, String role, long ttlSeconds) {
        var now = Instant.now();
        var claims = JwtClaimsSet.builder()
                .issuer(issuer)
                .subject(userId.toString())
                .audience(List.of(type.audience()))
                .issuedAt(now)
                .expiresAt(now.plusSeconds(ttlSeconds))
                .id(UUID.randomUUID().toString())
                .claim(TokenType.TYPE_CLAIM, type.type())
                .claim(TokenType.ROLE_CLAIM, role)
                .build();
        var header = JwsHeader.with(MacAlgorithm.HS256).type("JWT").build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    private JwtEncoder encoder(String secret) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(TokenDecoders.signingKey(secret)));
    }
}
