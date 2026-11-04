package com.iseonjwa.reservation.reservation.presentation.dto.response;

import com.iseonjwa.reservation.reservation.domain.entity.Reservation;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.Instant;

// 대사용 예매 한 건(ADR 0029). ID·상태·금액·시각만 담고 개인정보는 넣지 않는다
@Getter
public class ReconciliationReservationResponse {
    private final Long reservationId;
    private final Long userId;
    private final String status;
    private final BigDecimal totalAmount;
    private final Long paymentId;
    private final Instant holdExpiresAt;
    private final Instant updatedAt;

    private ReconciliationReservationResponse(Reservation reservation) {
        this.reservationId = reservation.getId();
        this.userId = reservation.getUserId();
        this.status = reservation.getStatus().name();
        this.totalAmount = reservation.getTotalAmount().toBigDecimal();
        this.paymentId = reservation.getPaymentId();
        this.holdExpiresAt = reservation.getHoldExpiresAt();
        this.updatedAt = reservation.getUpdatedAt();
    }

    public static ReconciliationReservationResponse of(Reservation reservation) {
        return new ReconciliationReservationResponse(reservation);
    }
}
