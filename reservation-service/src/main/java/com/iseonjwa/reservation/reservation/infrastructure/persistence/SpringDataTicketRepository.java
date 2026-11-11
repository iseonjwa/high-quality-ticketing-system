package com.iseonjwa.reservation.reservation.infrastructure.persistence;

import com.iseonjwa.reservation.reservation.domain.entity.Ticket;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

interface SpringDataTicketRepository extends JpaRepository<Ticket, Long> {

    @Modifying(flushAutomatically = true)
    @Query(nativeQuery = true, value = """
            UPDATE tickets
               SET status = 'VOID', updated_at = :now, updated_by = :actorId
             WHERE status = 'ISSUED'
               AND reservation_seat_id IN (SELECT id FROM reservation_seats WHERE reservation_id = :reservationId)
            """)
    int voidIssuedByReservation(@Param("reservationId") Long reservationId, @Param("now") Instant now,
            @Param("actorId") Long actorId);
}
