package com.iseonjwa.reservation.reservation.infrastructure.token;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jose.proc.SingleKeyJWSKeySelector;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import java.security.KeyFactory;
import java.security.interfaces.ECPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * 테스트 전용 P-256 키 쌍이다. 운영에서 쓰지 않는 값이고 application-test.yml의 {@code payment.token.private-key}와 같다.
 * 공개키는 결제 서비스 검증기가 받는 형식(X.509 SubjectPublicKeyInfo)이다.
 */
public final class PaymentTokenTestKeys {
    public static final String PRIVATE_KEY =
            "MIGHAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBG0wawIBAQQg+uDSDUG6QrvvqRqq1FhnvMZbZY7Ie0ZUXHgIAuLt8VGhRANCAARFtGpC6H+mpOYKlnwgBhJ3BfzloAU8FOl/WhZyfnsiZGnp73cwMzvB3RX3Im+69CHn3/w2T9kwUCq/s4Lrhnib";
    public static final String PUBLIC_KEY =
            "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAERbRqQuh/pqTmCpZ8IAYSdwX85aAFPBTpf1oWcn57ImRp6e93MDM7wd0V9yJvuvQh59/8Nk/ZMFAqv7OC64Z4mw==";
    // 위와 다른 쌍의 개인키. 다른 키로 서명한 토큰이 거절되는지 확인할 때 쓴다
    public static final String OTHER_PRIVATE_KEY =
            "MIGHAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBG0wawIBAQQgloz3eybLDVreZIkymUSPBJc+RiC386zYR7CGagWsMRuhRANCAAQ0sBtZM5hcjPSAX/Z9U1hIrdychKe/aA5+b94FiwtzsqdizNNOrxCrQhFxFUsb5K4wEphzDb0WrmY/TPr+dyUl";
    // P-384 개인키. 곡선이 다르면 기동을 막는지 확인할 때 쓴다
    public static final String P384_PRIVATE_KEY =
            "MIG2AgEAMBAGByqGSM49AgEGBSuBBAAiBIGeMIGbAgEBBDATngcoih4+tL/6r5rgKfTq1ckaKQlWSwfPlg+EJe/bdYpjyBvv2yTMVYtgZxNrx5ehZANiAAQi7GtI5+5m+ME50oTI+6YoNkQpsioeUHfBLYNfe2zCke+9mQ8PSm0IoqIYY60p31j2Cfr596mp9yTytDU/4m3tl3+pG9fl4sp44x2IDTmfCQ8T0B0oEpsYksg9OiWqtLo=";

    private PaymentTokenTestKeys() {}

    /** 서명만 보는 ES256 디코더. 만료는 보지 않는다(고정 시계로 만든 토큰이 실제 시각에는 이미 만료됐을 수 있어서다). */
    public static NimbusJwtDecoder decoder() {
        var processor = new DefaultJWTProcessor<SecurityContext>();
        processor.setJWSKeySelector(new SingleKeyJWSKeySelector<>(JWSAlgorithm.ES256, publicKey()));
        processor.setJWTClaimsSetVerifier((claims, context) -> { });
        var decoder = new NimbusJwtDecoder(processor);
        decoder.setJwtValidator(jwt -> OAuth2TokenValidatorResult.success());
        return decoder;
    }

    public static ECPublicKey publicKey() {
        try {
            return (ECPublicKey) KeyFactory.getInstance("EC")
                    .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(PUBLIC_KEY)));
        } catch (java.security.GeneralSecurityException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
