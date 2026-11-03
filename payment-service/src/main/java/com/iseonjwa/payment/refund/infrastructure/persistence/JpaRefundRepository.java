package com.iseonjwa.payment.refund.infrastructure.persistence;

import com.iseonjwa.payment.refund.domain.Refund;
import com.iseonjwa.payment.refund.domain.RefundReason;
import com.iseonjwa.payment.refund.domain.RefundRepository;
import com.iseonjwa.payment.refund.domain.RefundSource;
import com.iseonjwa.payment.refund.domain.RefundStatus;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JpaRefundRepository implements RefundRepository {
    private final SpringDataRefundRepository repository;

    public JpaRefundRepository(SpringDataRefundRepository repository) {
        this.repository = repository;
    }

    @Override
    public int createForApprovedPayment(Long paymentId, Long reservationId, UUID pgRefundId, RefundReason reason,
            RefundSource source, UUID sourceEventId, Instant now) {
        return repository.createForApprovedPayment(paymentId, reservationId, pgRefundId, reason.name(), source.name(),
                sourceEventId, now);
    }

    @Override
    public int createForAmountMismatch(Long paymentId, UUID pgRefundId, BigDecimal pgApprovedAmount, Instant now) {
        return repository.createForAmountMismatch(paymentId, pgRefundId, pgApprovedAmount, now);
    }

    @Override
    public Optional<Refund> findByPaymentId(Long paymentId) {
        return repository.findByPaymentId(paymentId);
    }

    // 행 잠금은 트랜잭션이 끝날 때까지 유지되므로, 트랜잭션 밖에서 부르면 잠금이 곧바로 풀려 SKIP LOCKED가 의미 없다
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<Refund> findDueForUpdate(Instant now) {
        return repository.findDueForUpdate(now);
    }

    @Override
    @Transactional
    public boolean claim(Long id, RefundStatus status, Instant leaseUntil, Instant now) {
        return repository.claim(id, status.name(), leaseUntil, now) == 1;
    }

    @Override
    @Transactional
    public boolean markRefunded(Long id, RefundStatus from, String pgRefundRef, Instant refundedAt, Instant now) {
        return repository.markRefunded(id, from.name(), pgRefundRef, refundedAt, now) == 1;
    }

    @Override
    @Transactional
    public boolean markUnknown(Long id, Instant nextCheckAt, Instant now) {
        return repository.markUnknown(id, nextCheckAt, now) == 1;
    }

    @Override
    @Transactional
    public boolean reschedule(Long id, Instant nextCheckAt, Instant now) {
        return repository.reschedule(id, nextCheckAt, now) == 1;
    }

    @Override
    @Transactional
    public boolean markNeedsManualCheck(Long id, RefundStatus from, Instant now) {
        return repository.markNeedsManualCheck(id, from.name(), now) == 1;
    }

    @Override
    @Transactional
    public boolean markFailed(Long id, RefundStatus from, Instant now) {
        return repository.markFailed(id, from.name(), now) == 1;
    }
}
