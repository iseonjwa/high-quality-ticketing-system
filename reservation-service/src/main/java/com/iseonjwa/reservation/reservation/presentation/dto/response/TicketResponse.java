package com.iseonjwa.reservation.reservation.presentation.dto.response;

import com.iseonjwa.reservation.reservation.domain.repository.ReservationSeatRepository.SeatDetailRow;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class TicketResponse {

    private String ticketNo;

    private Long seatId;

    public static TicketResponse from(SeatDetailRow seat) {
        return new TicketResponse(seat.ticketNo(), seat.seatId());
    }
}
