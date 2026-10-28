package com.iseonjwa.payment.payment.infrastructure.token;

import com.iseonjwa.common.response.BusinessException;
import com.iseonjwa.payment.global.PaymentTokenKey;
import com.iseonjwa.payment.payment.application.PaymentErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;

import java.time.Instant;
import java.util.List;

import static com.iseonjwa.payment.support.PaymentTokenFixtures.TEST_SECRET;
import static com.iseonjwa.payment.support.PaymentTokenFixtures.token;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JwtPaymentTokenVerifierTest {
    private static final String OTHER_SECRET = "ffeeddccbbaa99887766554433221100ffeeddccbbaa99887766554433221100";

    private final JwtPaymentTokenVerifier verifier = new JwtPaymentTokenVerifier(PaymentTokenKey.from(TEST_SECRET));

    @Test
    void 올바른_토큰의_사용자_예매_금액_만료를_돌려준다() {
        var expiresAt = Instant.now().plusSeconds(290).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);

        var result = verifier.verify(token(42, 7, 50000).expiresAt(expiresAt).build());

        assertEquals(42, result.userId());
        assertEquals(7, result.reservationId());
        assertEquals(0, result.amount().compareTo(java.math.BigDecimal.valueOf(50000)));
        assertEquals(expiresAt, result.expiresAt());
    }

    @Test
    void 만료된_토큰도_내용을_돌려주고_만료_판단은_호출한_쪽이_한다() {
        var result = verifier.verify(token(42, 7, 50000).expiresAt(Instant.now().minusSeconds(60)).build());

        assertTrue(result.isExpired(Instant.now()));
        assertFalse(result.isPastLookupGrace(Instant.now()), "만료 직후는 조회 전용으로 쓸 수 있다");
    }

    @Test
    void 만료_뒤_24시간이_지나면_조회에도_쓸_수_없다() {
        var result = verifier.verify(token(42, 7, 50000).expiresAt(Instant.now().minusSeconds(24 * 3600 + 5)).build());

        assertTrue(result.isPastLookupGrace(Instant.now()));
    }

    @Test
    void 다른_키로_서명한_토큰은_거절한다() {
        assertInvalid(token(42, 7, 50000).secret(OTHER_SECRET).build());
    }

    @Test
    void HS256이_아닌_알고리즘의_토큰은_거절한다() {
        assertInvalid(token(42, 7, 50000).algorithm(MacAlgorithm.HS512).build());
    }

    @Test
    void 서명을_제거한_alg_none_토큰은_거절한다() {
        var valid = token(42, 7, 50000).build();
        var parts = valid.split("\\.");
        var header = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"alg\":\"none\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        assertInvalid(header + "." + parts[1] + ".");
    }

    @Test
    void 내용을_바꾸면_서명이_맞지_않아_거절한다() {
        var parts = token(42, 7, 50000).build().split("\\.");
        var forged = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                "{\"sub\":\"1\",\"aud\":[\"payment-service\"],\"typ\":\"payment\",\"jti\":\"x\",\"exp\":9999999999,\"reservationId\":7,\"amount\":1}"
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));

        assertInvalid(parts[0] + "." + forged + "." + parts[2]);
    }

    @Test
    void 용도가_payment가_아니면_거절한다() {
        assertInvalid(token(42, 7, 50000).type("entry").build());
    }

    @Test
    void 대상이_결제_서비스가_아니면_거절한다() {
        assertInvalid(token(42, 7, 50000).audience(List.of("reservation-service")).build());
    }

    @Test
    void 예매_ID가_없으면_거절한다() {
        assertInvalid(token(42, 7, 50000).omitReservationId().build());
    }

    @Test
    void 정수가_아닌_금액_claim은_거절한다() {
        assertInvalid(token(42, 7, 50000).amountClaim(49999.5).build());
    }

    @Test
    void 길이_상한을_넘으면_파싱하지_않고_거절한다() {
        assertInvalid("a".repeat(JwtPaymentTokenVerifier.MAX_TOKEN_LENGTH + 1));
    }

    @Test
    void JWT_형식이_아니면_거절한다() {
        assertInvalid("not-a-jwt");
        assertInvalid("");
    }

    private void assertInvalid(String token) {
        var exception = assertThrows(BusinessException.class, () -> verifier.verify(token));
        assertEquals(PaymentErrorCode.PAYMENT_TOKEN_INVALID, exception.getErrorCode());
    }
}
