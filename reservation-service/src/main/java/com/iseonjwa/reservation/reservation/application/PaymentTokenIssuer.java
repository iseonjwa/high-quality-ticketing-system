package com.iseonjwa.reservation.reservation.application;

import java.time.Duration;
import java.time.Instant;

/**
 * 결제 토큰 발급(계약 ③, docs/design/payment/contracts.md 1절). 토큰 만료는 {@code holdExpiresAt - 10초}다.
 * 토큰 검증을 통과한 승인이 선점 만료 뒤에 도착하는 경우를 줄이려는 여유다(Gateway 타임아웃 3초 + 결과 반영 2초 + 여유 5초, SA 7-3).
 *
 * <p>토큰 claim(예매 ID·금액·만료)이 모두 예매의 값이라 예매 모듈이 소유한다. 선점 모듈은 ADR 0034가 허용한
 * {@code seathold → reservation} 방향으로 이 포트를 쓴다(ISE-273, docs/design/reservation/contracts.md 5-3절 4번).
 */
public interface PaymentTokenIssuer {
    // 멱등 재요청에 토큰을 줄 수 있는지도 이 값으로 판단한다. 발급 규칙과 판단 기준이 어긋나지 않게 포트에 둔다
    Duration EXPIRY_MARGIN = Duration.ofSeconds(10);

    IssuedPaymentToken issue(Long userId, Long reservationId, long amount, Instant holdExpiresAt);

    record IssuedPaymentToken(String token, Instant expiresAt) {}
}
