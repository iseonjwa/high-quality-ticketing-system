package com.iseonjwa.reservation.seathold.infrastructure.token;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;

import java.text.ParseException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static com.iseonjwa.reservation.seathold.infrastructure.token.PaymentTokenTestKeys.OTHER_PRIVATE_KEY;
import static com.iseonjwa.reservation.seathold.infrastructure.token.PaymentTokenTestKeys.P384_PRIVATE_KEY;
import static com.iseonjwa.reservation.seathold.infrastructure.token.PaymentTokenTestKeys.PRIVATE_KEY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

// 결제 서비스의 JwtPaymentTokenVerifier가 받는 형식(payment/contracts.md 1절)으로 ES256 발급하는지 확인한다
class JwtPaymentTokenIssuerTest {
    private static final Instant NOW = Instant.parse("2026-11-02T11:00:03Z");
    private static final Instant HOLD_EXPIRES_AT = Instant.parse("2026-11-02T11:05:03Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private final JwtPaymentTokenIssuer issuer = new JwtPaymentTokenIssuer(PRIVATE_KEY, CLOCK);

    @Test
    void 계약의_claim과_선점_만료_10초_전_만료로_ES256_발급한다() throws Exception {
        var issued = issuer.issue(42L, 501L, 330000L, HOLD_EXPIRES_AT);

        var jwt = SignedJWT.parse(issued.token());
        assertEquals(JWSAlgorithm.ES256, jwt.getHeader().getAlgorithm());
        assertEquals("JWT", jwt.getHeader().getType().getType());
        assertTrue(jwt.verify(new ECDSAVerifier(PaymentTokenTestKeys.publicKey())), "공개키로 서명이 검증돼야 한다");
        var claims = jwt.getJWTClaimsSet();
        assertEquals("payment", claims.getStringClaim("typ"));
        assertTrue(claims.getAudience().contains("payment-service"));
        assertEquals("42", claims.getSubject());
        assertNotNull(claims.getJWTID());
        assertEquals(HOLD_EXPIRES_AT.minusSeconds(10), claims.getExpirationTime().toInstant());
        assertEquals(HOLD_EXPIRES_AT.minusSeconds(10), issued.expiresAt());
    }

    @Test
    void 예매_ID와_금액은_JSON_정수로_담는다() throws Exception {
        var claims = SignedJWT.parse(issuer.issue(42L, 501L, 330000L, HOLD_EXPIRES_AT).token()).getJWTClaimsSet();

        // Payment 검증기는 문자열·소수인 예매 ID와 금액을 거절한다
        Object reservationId = claims.getClaim("reservationId");
        Object amount = claims.getClaim("amount");
        assertTrue(reservationId instanceof Long || reservationId instanceof Integer, reservationId.getClass().getName());
        assertTrue(amount instanceof Long || amount instanceof Integer, amount.getClass().getName());
        assertEquals(501L, ((Number) reservationId).longValue());
        assertEquals(330000L, ((Number) amount).longValue());
    }

    @Test
    void 발급마다_jti가_다르다() throws ParseException {
        var first = SignedJWT.parse(issuer.issue(42L, 501L, 330000L, HOLD_EXPIRES_AT).token()).getJWTClaimsSet();
        var second = SignedJWT.parse(issuer.issue(42L, 501L, 330000L, HOLD_EXPIRES_AT).token()).getJWTClaimsSet();

        assertFalse(first.getJWTID().equals(second.getJWTID()));
    }

    @Test
    void 다른_개인키로_발급한_토큰은_이_공개키로_검증되지_않는다() throws Exception {
        var other = new JwtPaymentTokenIssuer(OTHER_PRIVATE_KEY, CLOCK);

        var jwt = SignedJWT.parse(other.issue(42L, 501L, 330000L, HOLD_EXPIRES_AT).token());

        assertFalse(jwt.verify(new ECDSAVerifier(PaymentTokenTestKeys.publicKey())));
    }

    @Test
    void 키가_없거나_형식이_틀리거나_P256이_아니면_값을_노출하지_않고_기동을_막는다() {
        var notBase64 = "이것은-base64가-아니다";
        var notKey = "QUJDREVGRw==";

        assertThrows(IllegalStateException.class, () -> new JwtPaymentTokenIssuer("", CLOCK));
        assertThrows(IllegalStateException.class, () -> new JwtPaymentTokenIssuer("   ", CLOCK));
        var base64Error = assertThrows(IllegalStateException.class, () -> new JwtPaymentTokenIssuer(notBase64, CLOCK));
        var formatError = assertThrows(IllegalStateException.class, () -> new JwtPaymentTokenIssuer(notKey, CLOCK));
        var curveError = assertThrows(IllegalStateException.class, () -> new JwtPaymentTokenIssuer(P384_PRIVATE_KEY, CLOCK));
        assertFalse(base64Error.getMessage().contains(notBase64));
        assertFalse(formatError.getMessage().contains(notKey));
        assertFalse(curveError.getMessage().contains(P384_PRIVATE_KEY));
        assertTrue(curveError.getMessage().contains("P-256"));
    }
}
