package com.iseonjwa.payment.support;

import com.iseonjwa.common.auth.TokenDecoders;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import javax.crypto.spec.SecretKeySpec;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 테스트용 결제 토큰 발급기. 운영에서는 Reservation이 발급하므로 이 코드는 테스트에만 있다.
 * claim 이름은 JwtPaymentTokenVerifier의 가정과 같아야 한다.
 */
public final class PaymentTokenFixtures {
    // application-test.yml의 payment.token.secret과 같다
    public static final String TEST_SECRET = "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff";

    private PaymentTokenFixtures() {}

    public static Builder token(long userId, long reservationId, long amount) {
        return new Builder(userId, reservationId, Long.valueOf(amount));
    }

    public static final class Builder {
        private final long userId;
        private final long reservationId;
        private final Object amount;
        private String secret = TEST_SECRET;
        private String type = "payment";
        private List<String> audience = List.of("payment-service");
        private Instant expiresAt = Instant.now().plusSeconds(290);
        private boolean omitReservationId;
        private MacAlgorithm algorithm = MacAlgorithm.HS256;

        private Builder(long userId, long reservationId, Object amount) {
            this.userId = userId;
            this.reservationId = reservationId;
            this.amount = amount;
        }

        public Builder amountClaim(Object value) { return new Builder(userId, reservationId, value).copyFrom(this); }
        public Builder secret(String secret) { this.secret = secret; return this; }
        public Builder type(String type) { this.type = type; return this; }
        public Builder audience(List<String> audience) { this.audience = audience; return this; }
        public Builder expiresAt(Instant expiresAt) { this.expiresAt = expiresAt; return this; }
        public Builder omitReservationId() { this.omitReservationId = true; return this; }
        public Builder algorithm(MacAlgorithm algorithm) { this.algorithm = algorithm; return this; }

        private Builder copyFrom(Builder other) {
            this.secret = other.secret;
            this.type = other.type;
            this.audience = other.audience;
            this.expiresAt = other.expiresAt;
            this.omitReservationId = other.omitReservationId;
            this.algorithm = other.algorithm;
            return this;
        }

        public String build() {
            var claims = JwtClaimsSet.builder()
                    .subject(Long.toString(userId))
                    .audience(audience)
                    .id(UUID.randomUUID().toString())
                    // 이미 만료된 토큰도 만들 수 있어야 해서 발급 시각을 만료 시각보다 앞에 둔다
                    .issuedAt(expiresAt.isBefore(Instant.now()) ? expiresAt.minusSeconds(300) : Instant.now().minusSeconds(1))
                    .expiresAt(expiresAt)
                    .claim("typ", type)
                    .claim("amount", amount);
            if (!omitReservationId) claims.claim("reservationId", reservationId);
            var encoder = new NimbusJwtEncoder(new ImmutableSecret<>(
                    new SecretKeySpec(TokenDecoders.signingKey(secret), algorithm.getName().equals("HS512") ? "HmacSHA512" : "HmacSHA256")));
            return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(algorithm).build(), claims.build()))
                    .getTokenValue();
        }
    }
}
