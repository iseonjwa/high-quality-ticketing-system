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

    List<Reservation> findByIdInOrderByIdAsc(List<Long> ids);

    @Query(nativeQuery = true, value = """
            SELECT *
              FROM reservations
             WHERE status IN ('CONFIRMED', 'CANCELED')
               AND updated_at >= :from
               AND updated_at < :to
               AND id > :cursor
             ORDER BY id
             LIMIT :limit
            """)
    List<Reservation> findSettledChanges(@Param("from") Instant from, @Param("to") Instant to,
            @Param("cursor") long cursor, @Param("limit") int limit);

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

    // 결제 결과 소비자가 바꾼 행은 사람 감사자가 없어 updated_by를 비운다. 변경 이유는 상태 이력에 남긴다
    @Modifying(flushAutomatically = true)
    @Query(nativeQuery = true, value = """
            UPDATE reservations
               SET status = 'CONFIRMED', payment_id = :paymentId, updated_at = :now, updated_by = NULL
             WHERE id = :id
               AND status = 'HELD'
            """)
    int confirmHeld(@Param("id") Long id, @Param("paymentId") Long paymentId, @Param("now") Instant now);

    @Modifying(flushAutomatically = true)
    @Query(nativeQuery = true, value = """
            UPDATE reservations
               SET status = 'RELEASED', updated_at = :now, updated_by = NULL
             WHERE id = :id
               AND status = 'HELD'
            """)
    int releaseHeld(@Param("id") Long id, @Param("now") Instant now);

    // Note: 연장 여부를 따로 저장하지 않고 "기한이 생성 + 31분 안"이면 아직 연장 전으로 본다. 첫 선점은 생성 시각 기준 30분이고
    // 연장하면 생성 + 60분이 되기 때문이다. 상한(승계 마감 + 30분)에 걸려 줄어든 연장은 다음 만료 때 now >= limit라 다시 늘지 않는다
    @Modifying(flushAutomatically = true)
    @Query(nativeQuery = true, value = """
            UPDATE reservations
               SET hold_expires_at = LEAST(hold_expires_at + interval '30 minutes', :limit),
                   updated_at = :now, updated_by = :actorId
             WHERE id = :id
               AND status = 'HELD'
               AND source = 'LOTTERY'
               AND hold_expires_at <= :now
               AND hold_expires_at < created_at + interval '31 minutes'
               AND :now < :limit
            """)
    int extendLotteryHoldOnce(@Param("id") Long id, @Param("now") Instant now, @Param("limit") Instant limit,
                              @Param("actorId") Long actorId);
}
