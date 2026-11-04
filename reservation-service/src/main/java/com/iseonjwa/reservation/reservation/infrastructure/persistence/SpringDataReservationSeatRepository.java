package com.iseonjwa.reservation.reservation.infrastructure.persistence;

import com.iseonjwa.reservation.reservation.domain.entity.ReservationSeat;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

interface SpringDataReservationSeatRepository extends JpaRepository<ReservationSeat, Long> {
    List<ReservationSeat> findByReservationIdOrderByIdAsc(Long reservationId);

    @Modifying(flushAutomatically = true)
    @Query(nativeQuery = true, value = """
            UPDATE reservation_seats
               SET status = 'RELEASED', updated_at = :now, updated_by = :actorId
             WHERE reservation_id = :reservationId
               AND status = 'HELD'
            """)
    int releaseHeld(@Param("reservationId") Long reservationId, @Param("now") Instant now,
            @Param("actorId") Long actorId);

    @Modifying(flushAutomatically = true)
    @Query(nativeQuery = true, value = """
            UPDATE reservation_seats
               SET status = 'CONFIRMED', updated_at = :now, updated_by = NULL
             WHERE reservation_id = :reservationId
               AND status = 'HELD'
            """)
    int confirmHeld(@Param("reservationId") Long reservationId, @Param("now") Instant now);
}
