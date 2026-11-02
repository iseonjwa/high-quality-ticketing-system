package com.iseonjwa.reservation.queue.infrastructure;

import com.iseonjwa.common.auth.EntryTokenClaims;
import com.iseonjwa.common.auth.TokenDecoders;
import com.iseonjwa.reservation.queue.domain.EntryToken;
import com.iseonjwa.reservation.queue.domain.EntryTokenIssuer;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;

@Component
public class JwtEntryTokenIssuer implements EntryTokenIssuer {

    private final NimbusJwtEncoder nimbusJwtEncoder;

    public JwtEntryTokenIssuer(@Value("${entry-token.secret:}") String secret) {
        this.nimbusJwtEncoder = new NimbusJwtEncoder(new ImmutableSecret<>(TokenDecoders.signingKey(secret)));
    }

    @Override
    public EntryToken issue(long userId, long scheduleId, long issuedAtEpochMillis) {
        Instant issuedAt = Instant.ofEpochSecond(issuedAtEpochMillis/1000);
        Instant expiresAt = issuedAt.plus(EntryTokenClaims.TTL);
        String jti = jti(scheduleId, userId, issuedAtEpochMillis);

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .claim(EntryTokenClaims.TYPE, EntryTokenClaims.TYPE_VALUE)
                .audience(List.of(EntryTokenClaims.AUDIENCE_VALUE))
                .subject(String.valueOf(userId))
                .claim(EntryTokenClaims.SCHEDULE_ID, scheduleId)
                .expiresAt(expiresAt)
                .issuedAt(issuedAt)
                .id(jti)
                .build();

        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).type("JWT").build();
        String tokenValue = nimbusJwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new EntryToken(tokenValue, expiresAt);
    }

    private String jti(long scheduleId, long userId, long issuedAtEpochMillis) {
        String source = "entry:" + scheduleId + ":" + userId + ":" + issuedAtEpochMillis;
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(source.getBytes(StandardCharsets.UTF_8)); // 32바이트
            return HexFormat.of().formatHex(hash, 0, 16); // 앞 16바이트 -> 소문자 hex 32자
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다", e);
        }
    }

}
