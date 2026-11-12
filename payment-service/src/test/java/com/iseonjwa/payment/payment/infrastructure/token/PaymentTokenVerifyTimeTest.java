package com.iseonjwa.payment.payment.infrastructure.token;

import com.iseonjwa.payment.global.PaymentTokenKey;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Date;
import java.util.UUID;
import java.util.function.Supplier;

import static com.iseonjwa.payment.support.PaymentTokenFixtures.TEST_PUBLIC_KEY;
import static com.iseonjwa.payment.support.PaymentTokenFixtures.token;

// 결제 토큰 검증 한 번의 시간(ES256 대 예전 HS256)을 재서 출력한다(ISE-245, ADR 0054 "측정"). 값으로 실패시키지 않는다.
// 같은 JVM에서 예열 2,000회 뒤 5,000회를 잰다. ES256은 검증기 전체 경로(서명·claim 확인 포함), HS256은 예전 디코더의 서명 검증이다.
// 로컬 개발 장비의 값이라 배포 서버와 다르다
class PaymentTokenVerifyTimeTest {
    private static final int WARMUP = 2_000;
    private static final int RUNS = 5_000;

    @Test
    void ES256_검증과_HS256_검증의_시간을_출력한다() throws JOSEException {
        var verifier = new JwtPaymentTokenVerifier(PaymentTokenKey.from(TEST_PUBLIC_KEY));
        var es256Token = token(42, 7, 50000).build();
        var secret = "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff".getBytes(StandardCharsets.UTF_8);
        var hsDecoder = NimbusJwtDecoder.withSecretKey(new SecretKeySpec(secret, "HmacSHA256"))
                .macAlgorithm(MacAlgorithm.HS256).build();
        var hsToken = hs256Token(secret);

        report("ES256 검증(JwtPaymentTokenVerifier.verify)", measure(() -> verifier.verify(es256Token)));
        report("HS256 검증(예전 방식 디코더)", measure(() -> hsDecoder.decode(hsToken)));
    }

    private static String hs256Token(byte[] secret) throws JOSEException {
        var claims = new JWTClaimsSet.Builder().subject("42").audience("payment-service")
                .jwtID(UUID.randomUUID().toString()).expirationTime(new Date(System.currentTimeMillis() + 290_000))
                .claim("typ", "payment").claim("reservationId", 7L).claim("amount", 50000L).build();
        var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.HS256).build(), claims);
        jwt.sign(new MACSigner(secret));
        return jwt.serialize();
    }

    private static long[] measure(Supplier<Object> action) {
        for (var i = 0; i < WARMUP; i++) action.get();
        var nanos = new long[RUNS];
        for (var i = 0; i < RUNS; i++) {
            var start = System.nanoTime();
            action.get();
            nanos[i] = System.nanoTime() - start;
        }
        Arrays.sort(nanos);
        return nanos;
    }

    private static void report(String name, long[] sorted) {
        var average = Arrays.stream(sorted).average().orElse(0) / 1000.0;
        var p99 = sorted[(int) Math.ceil(sorted.length * 0.99) - 1] / 1000.0;
        System.out.printf("BENCH %s 평균 %.1fµs p99 %.1fµs (예열 %d회, 측정 %d회)%n", name, average, p99, WARMUP, RUNS);
    }
}
