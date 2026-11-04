package com.iseonjwa.payment.payment.infrastructure.persistence;

import com.iseonjwa.payment.payment.domain.PaymentStatusHistory;
import com.iseonjwa.payment.payment.domain.PaymentStatusHistoryRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class JpaPaymentStatusHistoryRepository implements PaymentStatusHistoryRepository {
    private final SpringDataPaymentStatusHistoryRepository repository;

    public JpaPaymentStatusHistoryRepository(SpringDataPaymentStatusHistoryRepository repository) {
        this.repository = repository;
    }

    @Override
    public PaymentStatusHistory save(PaymentStatusHistory history) {
        return repository.saveAndFlush(history);
    }

    @Override
    public List<PaymentStatusHistory> findByPaymentId(Long paymentId) {
        return repository.findByPaymentIdOrderByIdAsc(paymentId);
    }
}
