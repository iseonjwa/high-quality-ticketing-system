package com.iseonjwa.reservation.reservation.domain.repository;

import com.iseonjwa.reservation.reservation.domain.entity.ReservationSeat;

import java.time.Instant;
import java.util.List;

public interface ReservationSeatRepository {
    ReservationSeat save(ReservationSeat seat);

    // 활성 예매에 묶인 좌석이 섞여 있으면 ActiveSeatConflictException을 던진다
    List<ReservationSeat> saveAll(List<ReservationSeat> seats);

    List<ReservationSeat> findByReservationId(Long reservationId);

    // 예매에 묶인 HELD 좌석을 RELEASED로 바꾸고 바꾼 행 수를 돌려준다. 부분 유니크 인덱스에서 빠져 다시 선점할 수 있다(D2)
    int releaseHeld(Long reservationId, Instant now, Long actorId);
}
