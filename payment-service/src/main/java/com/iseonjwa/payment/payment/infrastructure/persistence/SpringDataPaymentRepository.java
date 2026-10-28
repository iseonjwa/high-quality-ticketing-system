package com.iseonjwa.payment.payment.infrastructure.persistence;

import com.iseonjwa.payment.payment.domain.Payment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

// Note: 전이 쿼리는 영속성 컨텍스트를 거치지 않으므로 실행 전 flush, 실행 후 clear로 엔티티 캐시가 낡지 않게 한다.
// 종결(APPROVED·DECLINED)과 NEEDS_MANUAL_CHECK는 next_check_at을 비워 스케줄러가 더 집지 않게 한다.
interface SpringDataPaymentRepository extends JpaRepository<Payment, Long> {
    Optional<Payment> findByReservationId(Long reservationId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(nativeQuery = true, value = """
            UPDATE payments
            SET status = 'APPROVED', pg_tx_id = :pgTxId, pg_approved_amount = :pgApprovedAmount,
                approved_at = :approvedAt, next_check_at = NULL, updated_at = :now, updated_by = NULL
            WHERE id = :id AND status = :from
            """)
    int approve(@Param("id") Long id, @Param("from") String from, @Param("pgTxId") String pgTxId,
            @Param("pgApprovedAmount") BigDecimal pgApprovedAmount, @Param("approvedAt") Instant approvedAt,
            @Param("now") Instant now);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(nativeQuery = true, value = """
            UPDATE payments
            SET status = 'DECLINED', decline_code = :reason, next_check_at = NULL, updated_at = :now, updated_by = NULL
            WHERE id = :id AND status = :from
            """)
    int decline(@Param("id") Long id, @Param("from") String from, @Param("reason") String reason,
            @Param("now") Instant now);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(nativeQuery = true, value = """
            UPDATE payments
            SET status = 'UNKNOWN', next_check_at = :nextCheckAt, updated_at = :now, updated_by = NULL
            WHERE id = :id AND status = :from
            """)
    int markUnknown(@Param("id") Long id, @Param("from") String from, @Param("nextCheckAt") Instant nextCheckAt,
            @Param("now") Instant now);

    // COALESCE: 재조회 소진처럼 PG 정보가 없는 전이가 이전에 저장한 값을 지우지 않게 한다
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(nativeQuery = true, value = """
            UPDATE payments
            SET status = 'NEEDS_MANUAL_CHECK', pg_tx_id = COALESCE(:pgTxId, pg_tx_id),
                pg_approved_amount = COALESCE(:pgApprovedAmount, pg_approved_amount),
                approved_at = COALESCE(:approvedAt, approved_at), next_check_at = NULL,
                updated_at = :now, updated_by = NULL
            WHERE id = :id AND status = :from
            """)
    int markNeedsManualCheck(@Param("id") Long id, @Param("from") String from, @Param("pgTxId") String pgTxId,
            @Param("pgApprovedAmount") BigDecimal pgApprovedAmount, @Param("approvedAt") Instant approvedAt,
            @Param("now") Instant now);
}
