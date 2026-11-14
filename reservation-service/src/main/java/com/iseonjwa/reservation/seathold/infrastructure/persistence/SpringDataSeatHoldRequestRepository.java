package com.iseonjwa.reservation.seathold.infrastructure.persistence;

import com.iseonjwa.reservation.seathold.domain.entity.SeatHoldRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

interface SpringDataSeatHoldRequestRepository extends JpaRepository<SeatHoldRequest, UUID> {
    // 엔티티의 상태·예매 컬럼은 updatable = false라 변경 감지로 바뀌지 않는다. 선점 결과는 조건부 UPDATE로만 기록한다
    @Modifying(flushAutomatically = true)
    @Query(nativeQuery = true, value = """
            UPDATE seat_hold_requests
               SET status = 'HELD', reservation_id = :reservationId, updated_at = :now, updated_by = :actorId
             WHERE request_id = :requestId
               AND status = 'REQUESTED'
            """)
    int markHeld(@Param("requestId") UUID requestId, @Param("reservationId") Long reservationId,
            @Param("now") Instant now, @Param("actorId") Long actorId);

    @Query(nativeQuery = true, value = """
            SELECT created_at FROM seat_hold_requests
             WHERE request_id = :requestId
               AND status = 'REQUESTED'
               FOR UPDATE
            """)
    Optional<Instant> lockRequestedCreatedAt(@Param("requestId") UUID requestId);

    @Modifying(flushAutomatically = true)
    @Query(nativeQuery = true, value = """
            UPDATE seat_hold_requests
               SET status = 'REJECTED', reason_code = :reasonCode, updated_at = :now
             WHERE request_id = :requestId
               AND status = 'REQUESTED'
            """)
    int markRejected(@Param("requestId") UUID requestId, @Param("reasonCode") String reasonCode,
            @Param("now") Instant now);

    // Note: SKIP LOCKED로 소비자가 잠근 행을 기다리지 않는다. 소비자가 그 행의 결과를 쓰고, 못 쓰면 다음 주기에 닫힌다.
    // (status, created_at) 인덱스로 오래된 순으로 고른다
    @Modifying(flushAutomatically = true)
    @Query(nativeQuery = true, value = """
            UPDATE seat_hold_requests
               SET status = 'REJECTED', reason_code = 'TIMEOUT', updated_at = :now
             WHERE request_id IN (
                   SELECT request_id FROM seat_hold_requests
                    WHERE status = 'REQUESTED'
                      AND created_at <= :createdBefore
                    ORDER BY created_at
                    LIMIT :limit
                      FOR UPDATE SKIP LOCKED)
            """)
    int rejectTimedOut(@Param("createdBefore") Instant createdBefore, @Param("now") Instant now,
            @Param("limit") int limit);

    // 부분 유니크 인덱스(uq_seat_hold_requests_entry_token_jti)와 같은 조건이라 그 인덱스로 찾는다
    @Query(nativeQuery = true, value = """
            SELECT EXISTS (
                SELECT 1 FROM seat_hold_requests
                 WHERE entry_token_jti = :jti
                   AND status <> 'REJECTED')
            """)
    boolean existsActiveByEntryTokenJti(@Param("jti") String entryTokenJti);
}
