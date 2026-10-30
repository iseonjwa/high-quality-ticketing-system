package com.iseonjwa.reservation.seathold.application;

import java.time.Instant;

/**
 * 결제 토큰 발급(계약 ③, docs/design/payment/contracts.md 1절). 토큰 만료는 {@code holdExpiresAt - 10초}다.
 * 토큰 검증을 통과한 승인이 선점 만료 뒤에 도착하는 경우를 줄이려는 여유다(Gateway 타임아웃 3초 + 결과 반영 2초 + 여유 5초, SA 7-3).
 *
 * <p>TODO: 지금은 선점 응답만 쓰므로 선점 모듈에 둔다. 예매 조회도 발급하게 되면 위치를 예매 흐름 담당과 정한다
 * (docs/design/reservation/contracts.md 5-3절 4번).
 */
public interface PaymentTokenIssuer {
    IssuedPaymentToken issue(Long userId, Long reservationId, long amount, Instant holdExpiresAt);

    record IssuedPaymentToken(String token, Instant expiresAt) {}
}
