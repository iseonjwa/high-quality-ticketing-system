package com.iseonjwa.payment.payment.infrastructure.token;

import com.iseonjwa.common.response.BusinessException;
import com.iseonjwa.payment.global.PaymentTokenKey;
import com.iseonjwa.payment.payment.application.PaymentErrorCode;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

import static com.iseonjwa.payment.support.PaymentTokenFixtures.OTHER_PRIVATE_KEY;
import static com.iseonjwa.payment.support.PaymentTokenFixtures.TEST_PUBLIC_KEY;
import static com.iseonjwa.payment.support.PaymentTokenFixtures.token;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JwtPaymentTokenVerifierTest {
    private final JwtPaymentTokenVerifier verifier = new JwtPaymentTokenVerifier(PaymentTokenKey.from(TEST_PUBLIC_KEY));

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
    void 다른_개인키로_서명한_토큰은_거절한다() {
        assertInvalid(token(42, 7, 50000).privateKey(OTHER_PRIVATE_KEY).build());
    }

    @Test
    void HS256으로_서명한_토큰은_거절한다() {
        // 예전 형식(공유 비밀 HMAC)이다. 어떤 키로 서명했든 ES256이 아니면 서명 검증 전에 거절한다
        assertInvalid(token(42, 7, 50000)
                .hs256With("00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff".getBytes(StandardCharsets.UTF_8))
                .build());
    }

    @Test
    void 공개키_바이트를_HMAC_키로_써서_만든_HS256_토큰은_거절한다() {
        // 알고리즘 혼동: 검증기가 헤더의 alg를 믿으면 공개키를 HMAC 키로 받아 위조가 통한다. ES256 고정이라 거절돼야 한다
        var derBytes = Base64.getDecoder().decode(TEST_PUBLIC_KEY);
        assertInvalid(token(42, 7, 50000).hs256With(derBytes).build());
        assertInvalid(token(42, 7, 50000).hs256With(TEST_PUBLIC_KEY.getBytes(StandardCharsets.UTF_8)).build());
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
