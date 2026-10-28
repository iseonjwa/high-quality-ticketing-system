package com.iseonjwa.payment.event.infrastructure;

import com.iseonjwa.payment.event.domain.ProcessedEventRepository;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.UUID;

@Repository
public class JpaProcessedEventRepository implements ProcessedEventRepository {
    private final EntityManager entityManager;

    public JpaProcessedEventRepository(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    // Note: 확인 뒤 삽입이 아니라 삽입을 먼저 시도한다. 동시에 같은 이벤트가 와도 기본키가 한 쪽만 통과시킨다.
    @Override
    public boolean markProcessed(UUID eventId, String consumerGroup, Instant processedAt) {
        var inserted = entityManager.createNativeQuery("""
                INSERT INTO processed_event (event_id, consumer_group, processed_at)
                VALUES (:eventId, :consumerGroup, :processedAt)
                ON CONFLICT (event_id, consumer_group) DO NOTHING
                """)
                .setParameter("eventId", eventId)
                .setParameter("consumerGroup", consumerGroup)
                .setParameter("processedAt", processedAt)
                .executeUpdate();
        return inserted == 1;
    }
}
