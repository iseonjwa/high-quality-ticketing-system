package com.iseonjwa.reservation.reservation.application;

import com.iseonjwa.reservation.reservation.domain.entity.Reservation;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;

/**
 * {@code payment.command}로 보내는 RefundRequested의 페이로드(SA 10-3, ADR 0027). 금액은 환불할 결제의 금액(승인 이벤트 값)이다.
 * 결제 서비스가 이 값을 결제 행과 다시 대조한다(ADR 0020).
 */
@Getter
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public class RefundRequestedPayload {
    private final Long reservationId;
    private final Long paymentId;
    private final Long userId;
    private final BigDecimal amount;
    private final String currency;
    private final String reason;

    public static RefundRequestedPayload of(PaymentResult approved, String reason) {
        return new RefundRequestedPayload(approved.getReservationId(), approved.getPaymentId(), approved.getUserId(),
                approved.getAmount(), approved.getCurrency(), reason);
    }

    // 사용자가 확정 예매를 취소했다. 환불할 결제는 예매에 남은 결제 ID이고 금액은 예매 총액이다
    public static RefundRequestedPayload canceled(Reservation reservation) {
        return new RefundRequestedPayload(reservation.getId(), reservation.getPaymentId(), reservation.getUserId(),
                reservation.getTotalAmount().toBigDecimal(), PaymentResultService.CURRENCY,
                PaymentResultService.CANCELED);
    }
}
