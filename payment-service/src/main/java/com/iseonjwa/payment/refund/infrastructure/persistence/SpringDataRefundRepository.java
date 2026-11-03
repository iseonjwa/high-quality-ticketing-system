package com.iseonjwa.payment.refund.infrastructure.persistence;

import com.iseonjwa.payment.refund.domain.Refund;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

interface SpringDataRefundRepository extends JpaRepository<Refund, Long> {
    Optional<Refund> findByPaymentId(Long paymentId);

    // 금액은 결제의 승인 금액을 복사한다. 이벤트 값은 쓰지 않는다(data-model.md 4절)
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(nativeQuery = true, value = """
            INSERT INTO refunds (payment_id, pg_refund_id, amount, reason, source, source_event_id,
                                 status, next_check_at, created_at, updated_at)
            SELECT p.id, :pgRefundId, p.pg_approved_amount, :reason, :source, :sourceEventId,
                   'REQUESTED', :now, :now, :now
              FROM payments p
             WHERE p.id = :paymentId AND p.reservation_id = :reservationId AND p.status = 'APPROVED'
            ON CONFLICT (payment_id) DO NOTHING
            """)
    int createForApprovedPayment(@Param("paymentId") Long paymentId, @Param("reservationId") Long reservationId,
            @Param("pgRefundId") UUID pgRefundId, @Param("reason") String reason, @Param("source") String source,
            @Param("sourceEventId") UUID sourceEventId, @Param("now") Instant now);

    // 금액 불일치 환불: 금액은 결제의 amount가 아니라 호출한 쪽이 넘긴 PG 승인 금액이다. 결제는 NEEDS_MANUAL_CHECK여야 한다
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(nativeQuery = true, value = """
            INSERT INTO refunds (payment_id, pg_refund_id, amount, reason, source, status, next_check_at, created_at,
                                 updated_at)
            SELECT p.id, :pgRefundId, :pgApprovedAmount, 'PG_AMOUNT_MISMATCH', 'OPS', 'REQUESTED', :now, :now, :now
              FROM payments p
             WHERE p.id = :paymentId AND p.status = 'NEEDS_MANUAL_CHECK'
            ON CONFLICT (payment_id) DO NOTHING
            """)
    int createForAmountMismatch(@Param("paymentId") Long paymentId, @Param("pgRefundId") UUID pgRefundId,
            @Param("pgApprovedAmount") BigDecimal pgApprovedAmount, @Param("now") Instant now);

    // 환불 워커의 대상 한 건을 잠근다. 조건은 idx_refunds_next_check_at 부분 인덱스와 같다. 다른 워커가 잠근 행은 건너뛴다
    @Query(nativeQuery = true, value = """
            SELECT * FROM refunds
            WHERE status IN ('REQUESTED', 'UNKNOWN') AND next_check_at <= :now
            ORDER BY next_check_at
            LIMIT 1
            FOR UPDATE SKIP LOCKED
            """)
    Optional<Refund> findDueForUpdate(@Param("now") Instant now);

    // COALESCE: 처음 PG를 부른 시각을 지키는 것이 "첫 호출은 refund, 이후는 findRefund 먼저" 판단의 근거다
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(nativeQuery = true, value = """
            UPDATE refunds
            SET next_check_at = :leaseUntil, attempt = attempt + 1, pg_requested_at = COALESCE(pg_requested_at, :now),
                updated_at = :now, updated_by = NULL
            WHERE id = :id AND status = :status AND next_check_at <= :now
            """)
    int claim(@Param("id") Long id, @Param("status") String status, @Param("leaseUntil") Instant leaseUntil,
            @Param("now") Instant now);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(nativeQuery = true, value = """
            UPDATE refunds
            SET status = 'REFUNDED', pg_refund_ref = :pgRefundRef, refunded_at = :refundedAt, next_check_at = NULL,
                updated_at = :now, updated_by = NULL
            WHERE id = :id AND status = :from
            """)
    int markRefunded(@Param("id") Long id, @Param("from") String from, @Param("pgRefundRef") String pgRefundRef,
            @Param("refundedAt") Instant refundedAt, @Param("now") Instant now);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(nativeQuery = true, value = """
            UPDATE refunds
            SET status = 'UNKNOWN', next_check_at = :nextCheckAt, updated_at = :now, updated_by = NULL
            WHERE id = :id AND status = 'REQUESTED'
            """)
    int markUnknown(@Param("id") Long id, @Param("nextCheckAt") Instant nextCheckAt, @Param("now") Instant now);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(nativeQuery = true, value = """
            UPDATE refunds
            SET next_check_at = :nextCheckAt, updated_at = :now, updated_by = NULL
            WHERE id = :id AND status = 'UNKNOWN'
            """)
    int reschedule(@Param("id") Long id, @Param("nextCheckAt") Instant nextCheckAt, @Param("now") Instant now);

    // from은 REQUESTED·UNKNOWN이다. 다른 경로가 이미 상태를 바꿨으면 조건이 맞지 않아 0행이다
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(nativeQuery = true, value = """
            UPDATE refunds
            SET status = 'NEEDS_MANUAL_CHECK', next_check_at = NULL, updated_at = :now, updated_by = NULL
            WHERE id = :id AND status = :from
            """)
    int markNeedsManualCheck(@Param("id") Long id, @Param("from") String from, @Param("now") Instant now);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(nativeQuery = true, value = """
            UPDATE refunds
            SET status = 'FAILED', next_check_at = NULL, updated_at = :now, updated_by = NULL
            WHERE id = :id AND status = :from
            """)
    int markFailed(@Param("id") Long id, @Param("from") String from, @Param("now") Instant now);
}
