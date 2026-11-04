package com.iseonjwa.reservation.reservation.presentation.dto.response;

import com.iseonjwa.reservation.reservation.domain.repository.ReservationSeatRepository.SeatDetailRow;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class ReservationSeatResponse {

    private Long seatId;

    private String section;

    private int row;

    private int number;

    private String grade;

    private long price;

    public static ReservationSeatResponse from(SeatDetailRow seat) {
        return new ReservationSeatResponse(seat.seatId(), seat.section(), seat.rowNo(), seat.seatNo(), seat.grade(),
                seat.price().toBigDecimal().longValueExact());
    }
}
