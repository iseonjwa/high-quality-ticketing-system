package com.iseonjwa.reservation.concert.presentation.dto.response;

import com.iseonjwa.reservation.concert.domain.repository.SeatLayoutRow;
import lombok.AllArgsConstructor;
import lombok.Getter;

// 좌석 하나. 누가 선점·구매했는지(HELD·SOLD)는 드러내지 않고 고를 수 있는지만 준다(SA 8-1)
@Getter
@AllArgsConstructor
public class SeatResponse {

    private Long seatId;

    private int row;

    private int number;

    // 일반 선점이 잡을 수 있으면 true. 추첨석(LOTTERY)과 이미 잡힌 좌석은 false다
    private boolean available;

    public static SeatResponse of(SeatLayoutRow seat, boolean available) {
        return new SeatResponse(seat.seatId(), seat.rowNo(), seat.seatNo(), available);
    }
}
