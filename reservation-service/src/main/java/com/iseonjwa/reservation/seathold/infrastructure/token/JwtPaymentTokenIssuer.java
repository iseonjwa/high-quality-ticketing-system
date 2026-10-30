package com.iseonjwa.reservation.seathold.infrastructure.token;

import com.iseonjwa.common.auth.TokenDecoders;
import com.iseonjwa.reservation.seathold.application.PaymentTokenIssuer;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * HS256 결제 토큰 발급(ADR 0025). claim 이름과 값은 Payment의 {@code PaymentTokenClaims}·{@code JwtPaymentTokenVerifier}와
 * 맞춘다. 한쪽을 바꾸면 결제 승인이 모두 403 {@code PAYMENT_TOKEN_INVALID}가 된다.
 */
@Component
public class JwtPaymentTokenIssuer implements PaymentTokenIssuer {
    static final String TYPE = "typ";
    static final String TYPE_VALUE = "payment";
    static final String AUDIENCE_VALUE = "payment-service";
    static final String RESERVATION_ID = "reservationId";
    static final String AMOUNT = "amount";
    static final Duration EXPIRY_MARGIN = Duration.ofSeconds(10);

    private static final int SECRET_LENGTH = 64;
    private static final Pattern LOWERCASE_HEX = Pattern.compile("[0-9a-f]+");

    private final JwtEncoder encoder;
    private final Clock clock;

    // Note: 키 값이 없거나 형식이 틀리면 기동을 막는다. 메시지에는 어느 조건이 틀렸는지만 쓰고 키 값은 넣지 않는다.
    // 키 바이트는 16진수 디코딩 없이 TokenDecoders.signingKey로 만들어야 Payment의 검증 키와 같아진다(0025).
    public JwtPaymentTokenIssuer(@Value("${payment.token.secret:}") String secret,
            @Qualifier("commonAuditingClock") Clock clock) {
        validate(secret);
        this.encoder = new NimbusJwtEncoder(new ImmutableSecret<>(TokenDecoders.signingKey(secret)));
        this.clock = clock;
    }

    @Override
    public IssuedPaymentToken issue(Long userId, Long reservationId, long amount, Instant holdExpiresAt) {
        var expiresAt = holdExpiresAt.minus(EXPIRY_MARGIN);
        var claims = JwtClaimsSet.builder()
                .subject(userId.toString())
                .audience(List.of(AUDIENCE_VALUE))
                .issuedAt(clock.instant())
                .expiresAt(expiresAt)
                .id(UUID.randomUUID().toString())
                .claim(TYPE, TYPE_VALUE)
                .claim(RESERVATION_ID, reservationId)
                .claim(AMOUNT, amount)
                .build();
        var header = JwsHeader.with(MacAlgorithm.HS256).type("JWT").build();
        var token = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new IssuedPaymentToken(token, expiresAt);
    }

    private static void validate(String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("PAYMENT_TOKEN_SECRET이 설정되지 않았습니다");
        }
        if (secret.length() != SECRET_LENGTH) {
            throw new IllegalStateException("PAYMENT_TOKEN_SECRET은 " + SECRET_LENGTH + "자여야 합니다");
        }
        if (!LOWERCASE_HEX.matcher(secret).matches()) {
            throw new IllegalStateException("PAYMENT_TOKEN_SECRET은 소문자 16진수여야 합니다");
        }
    }
}
