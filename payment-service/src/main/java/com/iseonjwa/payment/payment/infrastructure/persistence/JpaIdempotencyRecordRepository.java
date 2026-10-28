package com.iseonjwa.payment.payment.infrastructure.persistence;

import com.iseonjwa.payment.payment.domain.IdempotencyRecord;
import com.iseonjwa.payment.payment.domain.IdempotencyRecordId;
import com.iseonjwa.payment.payment.domain.IdempotencyRecordRepository;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JpaIdempotencyRecordRepository implements IdempotencyRecordRepository {
    private static final Duration RETENTION = Duration.ofHours(24);

    private final SpringDataIdempotencyRecordRepository repository;

    public JpaIdempotencyRecordRepository(SpringDataIdempotencyRecordRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<IdempotencyRecord> find(Long userId, UUID idemKey) {
        return repository.findById(new IdempotencyRecordId(userId, idemKey));
    }

    @Override
    public IdempotencyRecord save(IdempotencyRecord record) {
        return repository.saveAndFlush(record);
    }

    @Override
    public boolean linkAlias(Long userId, UUID idemKey, String requestHash, Long paymentId, Instant now) {
        return repository.linkAlias(userId, idemKey, requestHash, paymentId, now.plus(RETENTION), now) == 1;
    }

    @Override
    public boolean saveResponse(Long userId, UUID idemKey, int status, String body) {
        return repository.saveResponse(userId, idemKey, status, body) == 1;
    }
}
