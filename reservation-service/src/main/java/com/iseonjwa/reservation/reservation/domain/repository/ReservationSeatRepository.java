package com.iseonjwa.reservation.reservation.domain.repository;

import com.iseonjwa.reservation.reservation.domain.entity.ReservationSeat;

import java.util.List;

public interface ReservationSeatRepository {
    ReservationSeat save(ReservationSeat seat);

    List<ReservationSeat> saveAll(List<ReservationSeat> seats);

    List<ReservationSeat> findByReservationId(Long reservationId);
}
