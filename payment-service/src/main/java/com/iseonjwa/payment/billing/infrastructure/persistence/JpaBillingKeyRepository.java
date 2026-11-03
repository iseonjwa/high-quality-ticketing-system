package com.iseonjwa.payment.billing.infrastructure.persistence;

import com.iseonjwa.payment.billing.domain.BillingKey;
import com.iseonjwa.payment.billing.domain.BillingKeyRepository;
import com.iseonjwa.payment.billing.domain.BillingKeyStatus;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public class JpaBillingKeyRepository implements BillingKeyRepository {
    private final SpringDataBillingKeyRepository repository;

    public JpaBillingKeyRepository(SpringDataBillingKeyRepository repository) {
        this.repository = repository;
    }

    @Override
    public BillingKey save(BillingKey billingKey) {
        return repository.saveAndFlush(billingKey);
    }

    @Override
    public Optional<BillingKey> findActiveByUserId(Long userId) {
        return repository.findByUserIdAndStatus(userId, BillingKeyStatus.ACTIVE);
    }

    @Override
    public int revokeActive(Long userId) {
        return repository.revokeActive(userId);
    }
}
