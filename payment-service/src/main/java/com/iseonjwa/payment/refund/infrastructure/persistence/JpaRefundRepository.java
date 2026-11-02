package com.iseonjwa.payment.refund.infrastructure.persistence;

import com.iseonjwa.payment.refund.domain.Refund;
import com.iseonjwa.payment.refund.domain.RefundReason;
import com.iseonjwa.payment.refund.domain.RefundRepository;
import com.iseonjwa.payment.refund.domain.RefundSource;
import org.springframework.stereotype.Repository;

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
    public Optional<Refund> findByPaymentId(Long paymentId) {
        return repository.findByPaymentId(paymentId);
    }
}
