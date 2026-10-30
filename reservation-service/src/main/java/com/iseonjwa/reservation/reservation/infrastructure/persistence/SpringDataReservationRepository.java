package com.iseonjwa.reservation.reservation.infrastructure.persistence;

import com.iseonjwa.reservation.reservation.domain.entity.Reservation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

interface SpringDataReservationRepository extends JpaRepository<Reservation, Long> {
    Optional<Reservation> findByRequestId(UUID requestId);
}
