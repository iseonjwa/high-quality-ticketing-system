package com.iseonjwa.payment.refund.infrastructure.persistence;

import com.iseonjwa.payment.refund.domain.Refund;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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
}
