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

    long countByUserId(Long userId);

    // idx_reservations_user_id_created_at(user_id, created_at)를 탄다. 지워진 공연·회차도 이미 한 예매라 거르지 않는다
    @Query(nativeQuery = true, value = """
            SELECT r.id, r.reservation_no, c.title, s.starts_at, r.status, r.total_amount
              FROM reservations r
              JOIN schedules s ON s.id = r.schedule_id
              JOIN concerts c ON c.id = s.concert_id
             WHERE r.user_id = :userId
             ORDER BY r.created_at DESC, r.id DESC
             LIMIT :limit OFFSET :offset
            """)
    List<Object[]> findMineRows(@Param("userId") Long userId, @Param("limit") int limit, @Param("offset") long offset);

    // Note: 대사(ADR 0029)는 "기간 안에 확정·취소가 됨"을 현재 상태 + updated_at으로 대신 본다. 그래서 상태 전이 UPDATE는
    // 모두 updated_at을 갱신해야 한다. 취소(CONFIRMED → CANCELED)를 만들 때 빠뜨리면 결제 쪽이 RC-2를 찾지 못한다
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

    // Note: 이 트랜잭션이 끝날 때까지 행을 잠그고, 다른 인스턴스가 잠근 행은 기다리지 않고 건너뛴다.
    // 이 주기에서 실패한 예매(excludedIds)는 다시 집지 않는다. 비면 안 되므로 호출 쪽이 없는 ID(0)를 하나 넣는다
    @Query(nativeQuery = true, value = """
            SELECT id
              FROM reservations
             WHERE status = 'HELD'
               AND hold_expires_at <= :now
               AND id NOT IN (:excludedIds)
             ORDER BY hold_expires_at, id
             LIMIT 1
               FOR UPDATE SKIP LOCKED
            """)
    Optional<Long> lockNextExpiredHeldId(@Param("now") Instant now, @Param("excludedIds") List<Long> excludedIds);

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

    // updated_at을 반드시 갱신한다(위 대사 Note). 0행이면 서비스가 상태를 다시 읽으므로 영속성 컨텍스트를 비워
    // 방금 읽어 둔 CONFIRMED 엔티티가 아니라 DB의 현재 상태를 보게 한다
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(nativeQuery = true, value = """
            UPDATE reservations
               SET status = 'CANCELED', updated_at = :now, updated_by = :actorId
             WHERE id = :id
               AND status = 'CONFIRMED'
            """)
    int cancelConfirmed(@Param("id") Long id, @Param("now") Instant now, @Param("actorId") Long actorId);

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
