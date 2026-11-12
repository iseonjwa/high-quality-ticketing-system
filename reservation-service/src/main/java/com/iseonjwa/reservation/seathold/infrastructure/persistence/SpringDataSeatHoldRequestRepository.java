package com.iseonjwa.reservation.seathold.infrastructure.persistence;

import com.iseonjwa.reservation.seathold.domain.entity.SeatHoldRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
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
}
