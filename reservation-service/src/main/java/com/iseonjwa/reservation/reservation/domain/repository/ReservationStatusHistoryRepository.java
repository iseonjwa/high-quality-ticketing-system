package com.iseonjwa.reservation.reservation.domain.repository;

import com.iseonjwa.reservation.reservation.domain.entity.ReservationStatusHistory;

import java.util.List;

public interface ReservationStatusHistoryRepository {
    ReservationStatusHistory save(ReservationStatusHistory history);

    List<ReservationStatusHistory> findByReservationId(Long reservationId);
}
