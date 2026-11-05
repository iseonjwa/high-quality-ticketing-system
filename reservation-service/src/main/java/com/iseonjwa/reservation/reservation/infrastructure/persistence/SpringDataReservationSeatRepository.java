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

    // 지워진 회차 좌석도 이미 산 좌석이라 거르지 않는다. 티켓은 무효(VOID)가 아닌 것만 붙인다
    @Query(nativeQuery = true, value = """
            SELECT ss.id, ss.section, vs.row_no, vs.seat_no, ss.grade, rs.price, t.ticket_no
              FROM reservation_seats rs
              JOIN schedule_seats ss ON ss.id = rs.schedule_seat_id
              JOIN venue_seats vs ON vs.id = ss.venue_seat_id
              LEFT JOIN tickets t ON t.reservation_seat_id = rs.id AND t.status = 'ISSUED'
             WHERE rs.reservation_id = :reservationId
             ORDER BY ss.id
            """)
    List<Object[]> findSeatDetailRows(@Param("reservationId") Long reservationId);

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
