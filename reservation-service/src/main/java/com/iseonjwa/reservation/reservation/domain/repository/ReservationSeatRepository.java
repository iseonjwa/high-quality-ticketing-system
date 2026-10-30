package com.iseonjwa.reservation.reservation.domain.repository;

import com.iseonjwa.reservation.reservation.domain.entity.ReservationSeat;

import java.util.List;

public interface ReservationSeatRepository {
    ReservationSeat save(ReservationSeat seat);

    // 활성 예매에 묶인 좌석이 섞여 있으면 ActiveSeatConflictException을 던진다
    List<ReservationSeat> saveAll(List<ReservationSeat> seats);

    List<ReservationSeat> findByReservationId(Long reservationId);
}
