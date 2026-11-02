package com.iseonjwa.payment.payment.infrastructure.persistence;

import com.iseonjwa.payment.payment.domain.DeclineReason;
import com.iseonjwa.payment.payment.domain.Payment;
import com.iseonjwa.payment.payment.domain.PaymentRepository;
import com.iseonjwa.payment.payment.domain.PaymentStatus;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

@Repository
public class JpaPaymentRepository implements PaymentRepository {
    private final SpringDataPaymentRepository repository;

    public JpaPaymentRepository(SpringDataPaymentRepository repository) {
        this.repository = repository;
    }

    @Override
    public Payment save(Payment payment) {
        return repository.saveAndFlush(payment);
    }

    @Override
    public Optional<Payment> findById(Long id) {
        return repository.findById(id);
    }

    @Override
    public Optional<Payment> findByIdAndUserId(Long id, Long userId) {
        return repository.findByIdAndUserId(id, userId);
    }

    @Override
    public boolean existsById(Long id) {
        return repository.existsById(id);
    }

    @Override
    public Optional<Payment> findByReservationId(Long reservationId) {
        return repository.findByReservationId(reservationId);
    }

    // 행 잠금은 트랜잭션이 끝날 때까지 유지되므로, 트랜잭션 밖에서 부르면 잠금이 곧바로 풀려 SKIP LOCKED가 의미 없다
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<Payment> findDueForRecheckForUpdate(Instant now) {
        return repository.findDueForRecheckForUpdate(now);
    }

    @Override
    @Transactional
    public boolean claimRecheck(Long id, PaymentStatus status, Instant leaseUntil, Instant now) {
        return repository.claimRecheck(id, status.name(), leaseUntil, now) == 1;
    }

    @Override
    @Transactional
    public boolean rescheduleCheck(Long id, Instant nextCheckAt, Instant now) {
        return repository.rescheduleCheck(id, nextCheckAt, now) == 1;
    }

    @Override
    public boolean markApproved(Long id, PaymentStatus from, String pgTxId, BigDecimal pgApprovedAmount,
            Instant approvedAt, Instant now) {
        return repository.approve(id, from.name(), pgTxId, pgApprovedAmount, approvedAt, now) == 1;
    }

    @Override
    public boolean markDeclined(Long id, PaymentStatus from, DeclineReason reason, Instant now) {
        return repository.decline(id, from.name(), reason.name(), now) == 1;
    }

    @Override
    public boolean markUnknown(Long id, PaymentStatus from, Instant nextCheckAt, Instant now) {
        return repository.markUnknown(id, from.name(), nextCheckAt, now) == 1;
    }

    @Override
    public boolean markNeedsManualCheck(Long id, PaymentStatus from, String pgTxId, BigDecimal pgApprovedAmount,
            Instant approvedAt, Instant now) {
        return repository.markNeedsManualCheck(id, from.name(), pgTxId, pgApprovedAmount, approvedAt, now) == 1;
    }

    @Override
    public boolean markRefunded(Long id, PaymentStatus from, Instant refundedAt, Instant now) {
        return repository.markRefunded(id, from.name(), refundedAt, now) == 1;
    }
}
