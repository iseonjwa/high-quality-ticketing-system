package com.iseonjwa.payment.payment.application;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

/**
 * 서명·용도·대상 검증을 통과한 결제 토큰의 내용(SA 7-3). 만료 여부는 검증기가 아니라 여기서 판단해
 * "신규 승인 가능"과 "조회 전용"을 나눈다(0019).
 */
public record PaymentToken(long userId, long reservationId, BigDecimal amount, Instant expiresAt) {
    // 만료된 토큰으로 기존 결제를 조회할 수 있는 기간(멱등 기록 보관 기간과 같다)
    static final Duration LOOKUP_GRACE = Duration.ofHours(24);

    public boolean isExpired(Instant now) {
        return !now.isBefore(expiresAt);
    }

    // 만료 뒤 24시간을 넘기면 조회에도 쓰지 못한다
    public boolean isPastLookupGrace(Instant now) {
        return now.isAfter(expiresAt.plus(LOOKUP_GRACE));
    }
}
