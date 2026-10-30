package com.iseonjwa.reservation.reservation.infrastructure.persistence;

import com.iseonjwa.reservation.reservation.domain.entity.ReservationSeat;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

interface SpringDataReservationSeatRepository extends JpaRepository<ReservationSeat, Long> {
    List<ReservationSeat> findByReservationIdOrderByIdAsc(Long reservationId);
}
