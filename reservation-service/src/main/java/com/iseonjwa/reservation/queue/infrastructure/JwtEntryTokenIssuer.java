package com.iseonjwa.reservation.queue.infrastructure;

import com.iseonjwa.common.auth.EntryTokenClaims;
import com.iseonjwa.common.auth.TokenDecoders;
import com.iseonjwa.reservation.queue.domain.EntryToken;
import com.iseonjwa.reservation.queue.domain.EntryTokenIssuer;
import com.iseonjwa.reservation.queue.domain.EntryTokenJti;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Component;

import java.time.Instant;
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
        String jti = EntryTokenJti.of(scheduleId, userId, issuedAtEpochMillis);

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

}
