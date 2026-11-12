package com.iseonjwa.reservation.seathold.infrastructure.token;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

// 결제 토큰 서명 한 번의 시간(ES256 대 예전 HS256)을 재서 출력한다(ISE-245, ADR 0054 "측정"). 값으로 실패시키지 않는다.
// 같은 JVM에서 예열 2,000회 뒤 5,000회를 잰다. 로컬 개발 장비의 값이라 배포 서버와 다르다
class PaymentTokenSigningTimeTest {
    private static final int WARMUP = 2_000;
    private static final int RUNS = 5_000;
    private static final Instant NOW = Instant.parse("2026-11-12T11:00:00Z");
    private static final Instant HOLD_EXPIRES_AT = Instant.parse("2026-11-12T11:05:00Z");

    @Test
    void ES256_서명과_HS256_서명의_시간을_출력한다() {
        var issuer = new JwtPaymentTokenIssuer(PaymentTokenTestKeys.PRIVATE_KEY, Clock.fixed(NOW, ZoneOffset.UTC));
        var hsSecret = "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff".getBytes(StandardCharsets.UTF_8);
        var hsEncoder = new NimbusJwtEncoder(new ImmutableSecret<>(new SecretKeySpec(hsSecret, "HmacSHA256")));

        report("ES256 서명(JwtPaymentTokenIssuer.issue)", measure(() -> issuer.issue(42L, 501L, 330000L, HOLD_EXPIRES_AT)));
        report("HS256 서명(예전 방식)", measure(() -> hs256(hsEncoder)));
    }

    private static Object hs256(NimbusJwtEncoder encoder) {
        var claims = JwtClaimsSet.builder().subject("42").audience(List.of("payment-service")).issuedAt(NOW)
                .expiresAt(HOLD_EXPIRES_AT.minusSeconds(10)).id(UUID.randomUUID().toString())
                .claim("typ", "payment").claim("reservationId", 501L).claim("amount", 330000L).build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).type("JWT").build(), claims));
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
