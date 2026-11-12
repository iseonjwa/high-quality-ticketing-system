package com.iseonjwa.reservation.concert.domain.repository;

import com.iseonjwa.reservation.concert.domain.entity.ScheduleSeat;
import com.iseonjwa.reservation.concert.domain.entity.ScheduleSeatStatus;
import com.iseonjwa.reservation.concert.domain.entity.SeatGrade;

// 회차 좌석의 바뀌는 값. 선점·결제가 상태를, 추첨 설정이 등급을 바꾼다
public record SeatStateRow(Long seatId, SeatGrade grade, ScheduleSeatStatus status) {

    public boolean isHoldable() {
        return ScheduleSeat.isHoldable(status, grade);
    }
}
