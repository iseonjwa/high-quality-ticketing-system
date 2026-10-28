package com.iseonjwa.payment.event.infrastructure;

import com.iseonjwa.payment.event.domain.OutboxEvent;
import com.iseonjwa.payment.event.domain.OutboxRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Repository
public class JpaOutboxRepository implements OutboxRepository {
    private final SpringDataOutboxRepository repository;

    public JpaOutboxRepository(SpringDataOutboxRepository repository) {
        this.repository = repository;
    }

    @Override
    public OutboxEvent save(OutboxEvent event) {
        return repository.saveAndFlush(event);
    }

    // Note: 행 잠금(FOR UPDATE)과 임대 갱신은 한 트랜잭션이다. 커밋하면 잠금은 풀리고 임대만 남는다.
    @Override
    @Transactional
    public List<OutboxEvent> claimNext(Instant now, Instant leaseUntil, int batchSize) {
        var events = repository.lockPending(now, batchSize);
        if (!events.isEmpty()) {
            repository.lease(events.stream().map(OutboxEvent::getId).toList(), leaseUntil);
        }
        return events;
    }

    @Override
    @Transactional
    public boolean markSent(Long id, Instant sentAt) {
        return repository.markSent(id, sentAt) == 1;
    }

    @Override
    @Transactional
    public void recordFailure(Long id, String error) {
        repository.recordFailure(id, error);
    }
}
