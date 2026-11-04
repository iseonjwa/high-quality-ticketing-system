package com.iseonjwa.reservation.seathold.infrastructure.token;

import com.iseonjwa.common.auth.TokenDecoders;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import javax.crypto.spec.SecretKeySpec;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

// 결제 서비스의 JwtPaymentTokenVerifier가 받는 형식(payment/contracts.md 1절)으로 발급하는지 확인한다
class JwtPaymentTokenIssuerTest {
    private static final String SECRET = "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff";
    private static final Instant NOW = Instant.parse("2026-11-02T11:00:03Z");
    private static final Instant HOLD_EXPIRES_AT = Instant.parse("2026-11-02T11:05:03Z");

    private final JwtPaymentTokenIssuer issuer =
            new JwtPaymentTokenIssuer(SECRET, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void 계약의_claim과_선점_만료_10초_전_만료로_발급한다() {
        var issued = issuer.issue(42L, 501L, 330000L, HOLD_EXPIRES_AT);

        var jwt = decoder().decode(issued.token());
        assertEquals("HS256", jwt.getHeaders().get("alg"));
        assertEquals("payment", jwt.getClaimAsString("typ"));
        assertTrue(jwt.getAudience().contains("payment-service"));
        assertEquals("42", jwt.getSubject());
        assertNotNull(jwt.getId());
        assertEquals(HOLD_EXPIRES_AT.minusSeconds(10), jwt.getExpiresAt());
        assertEquals(HOLD_EXPIRES_AT.minusSeconds(10), issued.expiresAt());
    }

    @Test
    void 예매_ID와_금액은_JSON_정수로_담는다() {
        var jwt = decoder().decode(issuer.issue(42L, 501L, 330000L, HOLD_EXPIRES_AT).token());

        // Payment 검증기는 문자열·소수인 예매 ID와 금액을 거절한다
        Object reservationId = jwt.getClaim("reservationId");
        Object amount = jwt.getClaim("amount");
        assertTrue(reservationId instanceof Long || reservationId instanceof Integer, reservationId.getClass().getName());
        assertTrue(amount instanceof Long || amount instanceof Integer, amount.getClass().getName());
        assertEquals(501L, ((Number) reservationId).longValue());
        assertEquals(330000L, ((Number) amount).longValue());
    }

    @Test
    void 발급마다_jti가_다르다() {
        var first = decoder().decode(issuer.issue(42L, 501L, 330000L, HOLD_EXPIRES_AT).token());
        var second = decoder().decode(issuer.issue(42L, 501L, 330000L, HOLD_EXPIRES_AT).token());

        assertFalse(first.getId().equals(second.getId()));
    }

    @Test
    void 키가_없거나_형식이_틀리면_값을_노출하지_않고_기동을_막는다() {
        var clock = Clock.fixed(NOW, ZoneOffset.UTC);
        var shortKey = "abc123";
        var upperKey = SECRET.toUpperCase();

        assertThrows(IllegalStateException.class, () -> new JwtPaymentTokenIssuer("", clock));
        var shortError = assertThrows(IllegalStateException.class, () -> new JwtPaymentTokenIssuer(shortKey, clock));
        var upperError = assertThrows(IllegalStateException.class, () -> new JwtPaymentTokenIssuer(upperKey, clock));
        assertFalse(shortError.getMessage().contains(shortKey));
        assertFalse(upperError.getMessage().contains(upperKey));
    }

    // 만료 검사는 빼고 서명만 본다. 고정 시계로 만든 토큰이 실제 시각에는 이미 만료됐을 수 있어서다
    private static NimbusJwtDecoder decoder() {
        var decoder = NimbusJwtDecoder
                .withSecretKey(new SecretKeySpec(TokenDecoders.signingKey(SECRET), "HmacSHA256"))
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        decoder.setJwtValidator(jwt -> OAuth2TokenValidatorResult.success());
        return decoder;
    }
}
