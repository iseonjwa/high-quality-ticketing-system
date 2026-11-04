package com.iseonjwa.reservation.reservation.infrastructure.persistence;

import com.iseonjwa.reservation.reservation.domain.entity.ReservationStatusHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

interface SpringDataReservationStatusHistoryRepository extends JpaRepository<ReservationStatusHistory, Long> {
    List<ReservationStatusHistory> findByReservationIdOrderByIdAsc(Long reservationId);
}
