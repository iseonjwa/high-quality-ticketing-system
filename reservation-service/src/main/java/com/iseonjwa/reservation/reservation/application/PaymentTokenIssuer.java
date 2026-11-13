package com.iseonjwa.reservation.reservation.application;

import com.iseonjwa.reservation.reservation.domain.entity.Reservation;

import java.time.Instant;

/**
 * 결제 토큰 발급(계약 ③, docs/design/payment/contracts.md 1절). 토큰 만료는
 * {@code holdExpiresAt - }{@link Reservation#PAYMENT_CLOSE_MARGIN}이고, 토큰을 줄지는 {@link Reservation#isPayableAt}로
 * 판단한다. 발급 규칙과 판단 기준이 같은 상수를 써서 어긋나지 않는다.
 *
 * <p>토큰 claim(예매 ID·금액·만료)이 모두 예매의 값이라 예매 모듈이 소유한다. 선점 모듈은 ADR 0034가 허용한
 * {@code seathold → reservation} 방향으로 이 포트를 쓴다(ISE-273, docs/design/reservation/contracts.md 5-3절 4번).
 */
public interface PaymentTokenIssuer {
    IssuedPaymentToken issue(Long userId, Long reservationId, long amount, Instant holdExpiresAt);

    record IssuedPaymentToken(String token, Instant expiresAt) {}
}
