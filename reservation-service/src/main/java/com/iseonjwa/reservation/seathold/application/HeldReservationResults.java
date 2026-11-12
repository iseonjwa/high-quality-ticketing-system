package com.iseonjwa.reservation.seathold.application;

import com.iseonjwa.reservation.reservation.domain.entity.Reservation;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 예매 한 건을 선점 결과(contracts.md 2-1절 형식)로 만든다. 동기형 201 응답과 접수형 결과 조회의 {@code reservation}
 * 블록이 같은 필드·같은 토큰 규칙을 쓰게 한 곳에 둔다.
 */
@Component
class HeldReservationResults {
    private final PaymentTokenIssuer paymentTokenIssuer;

    HeldReservationResults(PaymentTokenIssuer paymentTokenIssuer) {
        this.paymentTokenIssuer = paymentTokenIssuer;
    }

    // 조회·재요청에도 새로 서명한다. exp는 선점 만료에서 계산해 첫 토큰과 같고 jti만 다르다(결제 토큰 폐기 목록 없음)
    SeatHoldResult withToken(Reservation reservation, List<Long> seatIds) {
        var token = paymentTokenIssuer.issue(reservation.getUserId(), reservation.getId(),
                reservation.getTotalAmount().toBigDecimal().longValueExact(), reservation.getHoldExpiresAt());
        return result(reservation, seatIds, token);
    }

    // 결제할 수 없는 예매(확정·만료·해제·취소)는 토큰을 주지 않는다
    SeatHoldResult withoutToken(Reservation reservation, List<Long> seatIds) {
        return result(reservation, seatIds, null);
    }

    private static SeatHoldResult result(Reservation reservation, List<Long> seatIds,
            PaymentTokenIssuer.IssuedPaymentToken token) {
        return new SeatHoldResult(reservation.getRequestId(), reservation.getId(), reservation.getReservationNo(),
                reservation.getScheduleId(), seatIds, reservation.getStatus(), reservation.getHoldExpiresAt(),
                reservation.getTotalAmount(), token == null ? null : token.token(),
                token == null ? null : token.expiresAt());
    }
}
