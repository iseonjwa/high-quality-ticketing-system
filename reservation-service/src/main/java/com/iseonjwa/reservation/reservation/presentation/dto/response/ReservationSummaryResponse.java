package com.iseonjwa.reservation.reservation.presentation.dto.response;

import com.iseonjwa.reservation.reservation.domain.repository.ReservationRepository.MyReservationRow;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.OffsetDateTime;
import java.time.ZoneId;

// 내 예매 목록의 한 줄
@Getter
@AllArgsConstructor
public class ReservationSummaryResponse {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private Long reservationId;

    private String reservationNo;

    private String concertTitle;

    private OffsetDateTime startsAt;

    private String status;

    private long totalAmount;

    public static ReservationSummaryResponse from(MyReservationRow row) {
        return new ReservationSummaryResponse(row.reservationId(), row.reservationNo(), row.concertTitle(),
                row.startsAt().atZone(KST).toOffsetDateTime(), row.status().name(),
                row.totalAmount().toBigDecimal().longValueExact());
    }
}
