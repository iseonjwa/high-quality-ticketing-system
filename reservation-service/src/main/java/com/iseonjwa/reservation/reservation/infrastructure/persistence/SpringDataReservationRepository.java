package com.iseonjwa.reservation.reservation.infrastructure.persistence;

import com.iseonjwa.reservation.reservation.domain.entity.Reservation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface SpringDataReservationRepository extends JpaRepository<Reservation, Long> {
    Optional<Reservation> findByRequestId(UUID requestId);

    // idx_reservations_status_hold_expires_at(status, hold_expires_at)를 탄다
    @Query(nativeQuery = true, value = """
            SELECT id
              FROM reservations
             WHERE status = 'HELD'
               AND hold_expires_at <= :now
             ORDER BY hold_expires_at, id
             LIMIT :limit
            """)
    List<Long> findExpiredHeldIds(@Param("now") Instant now, @Param("limit") int limit);

    // status는 엔티티에서 updatable = false라 변경 감지가 아닌 SQL로 바꾼다
    @Modifying(flushAutomatically = true)
    @Query(nativeQuery = true, value = """
            UPDATE reservations
               SET status = 'EXPIRED', updated_at = :now, updated_by = :actorId
             WHERE id = :id
               AND status = 'HELD'
               AND hold_expires_at <= :now
            """)
    int expireHeld(@Param("id") Long id, @Param("now") Instant now, @Param("actorId") Long actorId);
}
