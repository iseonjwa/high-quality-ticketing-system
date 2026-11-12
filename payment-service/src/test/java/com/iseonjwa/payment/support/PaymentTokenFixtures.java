package com.iseonjwa.payment.support;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.interfaces.ECPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/**
 * 테스트용 결제 토큰 발급기. 운영에서는 Reservation이 개인키로 발급하므로 이 코드는 테스트에만 있다.
 * claim 이름은 JwtPaymentTokenVerifier의 가정과 같아야 한다. 키는 모두 테스트 전용 P-256 값이고 운영에서 쓰지 않는다.
 */
public final class PaymentTokenFixtures {
    // 테스트 전용 키 쌍(PKCS#8 DER, X.509 SubjectPublicKeyInfo DER의 Base64). 공개키는 application-test.yml의 payment.token.public-key와 같다
    public static final String TEST_PRIVATE_KEY =
            "MIGHAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBG0wawIBAQQg+uDSDUG6QrvvqRqq1FhnvMZbZY7Ie0ZUXHgIAuLt8VGhRANCAARFtGpC6H+mpOYKlnwgBhJ3BfzloAU8FOl/WhZyfnsiZGnp73cwMzvB3RX3Im+69CHn3/w2T9kwUCq/s4Lrhnib";
    public static final String TEST_PUBLIC_KEY =
            "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAERbRqQuh/pqTmCpZ8IAYSdwX85aAFPBTpf1oWcn57ImRp6e93MDM7wd0V9yJvuvQh59/8Nk/ZMFAqv7OC64Z4mw==";
    // 위와 짝이 아닌 테스트 전용 개인키. 다른 키로 서명한 토큰이 거절되는지 확인한다
    public static final String OTHER_PRIVATE_KEY =
            "MIGHAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBG0wawIBAQQgloz3eybLDVreZIkymUSPBJc+RiC386zYR7CGagWsMRuhRANCAAQ0sBtZM5hcjPSAX/Z9U1hIrdychKe/aA5+b94FiwtzsqdizNNOrxCrQhFxFUsb5K4wEphzDb0WrmY/TPr+dyUl";
    // P-384 테스트 전용 공개키. 곡선이 다르면 기동을 막는지 확인한다
    public static final String P384_PUBLIC_KEY =
            "MHYwEAYHKoZIzj0CAQYFK4EEACIDYgAECgi2vtYo5rVYc2U3B8mL05LWqr3F4vQjcQn491dP9hgw11DGi7LPwvGkmJRjBTsIXX7Sz7GHPZ8h0fAyvb00GwfJMH7LktWydsocXK5OYtDIETTkCI10e0qHK7a6/8ht";
    // RSA 테스트 전용 공개키. EC가 아니면 기동을 막는지 확인한다
    public static final String RSA_PUBLIC_KEY =
            "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAzblMSovvhy5EOoXedHPdr7rIPB0ZBXl7hhE3C8o8e4HrWBSBoRMAPiH8EgwyesfuAoMXZpdzxe+G0J0pJbFA7aBPXnmupddagLtW4GWMoPmd9cuw39ejkAcT0jtMyRqzIoad6OI7OAd33H+z0m0i4ve+FSVQSQUkvTQDhNxiqujWz9Ksf+VCesJ3QAtcuojAt+ZEbdio7hzNswKoYMIKxidACyhfufc6I0Ygq5Tzb6rc31D00Z3TQSNip1p80YXkROGHmBrE61y/8h5oWfEFg8wYPZa5RswgnWi1hNDUP4kxizCWfU0fN34hMvhAKDQmXEjok6eCtvVqCvjMWtLaTQIDAQAB";

    private PaymentTokenFixtures() {}

    public static Builder token(long userId, long reservationId, long amount) {
        return new Builder(userId, reservationId, Long.valueOf(amount));
    }

    public static final class Builder {
        private final long userId;
        private final long reservationId;
        private final Object amount;
        private String privateKey = TEST_PRIVATE_KEY;
        private byte[] hmacKey;
        private String type = "payment";
        private List<String> audience = List.of("payment-service");
        private Instant expiresAt = Instant.now().plusSeconds(290);
        private boolean omitReservationId;

        private Builder(long userId, long reservationId, Object amount) {
            this.userId = userId;
            this.reservationId = reservationId;
            this.amount = amount;
        }

        public Builder amountClaim(Object value) { return new Builder(userId, reservationId, value).copyFrom(this); }
        /** 다른 개인키(PKCS#8 DER의 Base64)로 ES256 서명한다 */
        public Builder privateKey(String privateKey) { this.privateKey = privateKey; return this; }
        /** ES256 대신 이 바이트를 HMAC 키로 HS256 서명한다. 알고리즘 혼동 방어 테스트용이다 */
        public Builder hs256With(byte[] key) { this.hmacKey = key.clone(); return this; }
        public Builder type(String type) { this.type = type; return this; }
        public Builder audience(List<String> audience) { this.audience = audience; return this; }
        public Builder expiresAt(Instant expiresAt) { this.expiresAt = expiresAt; return this; }
        public Builder omitReservationId() { this.omitReservationId = true; return this; }

        private Builder copyFrom(Builder other) {
            this.privateKey = other.privateKey;
            this.hmacKey = other.hmacKey;
            this.type = other.type;
            this.audience = other.audience;
            this.expiresAt = other.expiresAt;
            this.omitReservationId = other.omitReservationId;
            return this;
        }

        public String build() {
            // 이미 만료된 토큰도 만들 수 있어야 해서 발급 시각을 만료 시각보다 앞에 둔다
            var issuedAt = expiresAt.isBefore(Instant.now()) ? expiresAt.minusSeconds(300) : Instant.now().minusSeconds(1);
            var claims = new JWTClaimsSet.Builder()
                    .subject(Long.toString(userId))
                    .audience(audience)
                    .jwtID(UUID.randomUUID().toString())
                    .issueTime(Date.from(issuedAt))
                    .expirationTime(Date.from(expiresAt))
                    .claim("typ", type)
                    .claim("amount", amount);
            if (!omitReservationId) claims.claim("reservationId", reservationId);
            try {
                var jwt = new SignedJWT(new JWSHeader.Builder(hmacKey == null ? JWSAlgorithm.ES256 : JWSAlgorithm.HS256)
                        .type(JOSEObjectType.JWT).build(), claims.build());
                jwt.sign(signer());
                return jwt.serialize();
            } catch (JOSEException exception) {
                throw new IllegalStateException(exception);
            }
        }

        private JWSSigner signer() throws JOSEException {
            return hmacKey != null ? new MACSigner(hmacKey) : new ECDSASigner(ecPrivateKey(privateKey));
        }
    }

    private static ECPrivateKey ecPrivateKey(String base64) {
        try {
            return (ECPrivateKey) KeyFactory.getInstance("EC")
                    .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(base64)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
