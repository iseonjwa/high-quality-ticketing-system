package com.iseonjwa.reservation.reservation.presentation.dto.response;

import com.iseonjwa.reservation.reservation.application.ReservationCancelResult;
import com.iseonjwa.reservation.reservation.domain.entity.ReservationStatus;

// 환불 상태는 paymentId로 결제 조회 API를 불러 본다
public record ReservationCancelResponse(Long reservationId, String status, Long paymentId) {

    public static ReservationCancelResponse from(ReservationCancelResult result) {
        return new ReservationCancelResponse(result.reservationId(), ReservationStatus.CANCELED.name(),
                result.paymentId());
    }
}
