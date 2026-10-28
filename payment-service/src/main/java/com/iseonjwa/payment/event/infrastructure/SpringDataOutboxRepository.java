package com.iseonjwa.payment.event.infrastructure;

import com.iseonjwa.payment.event.domain.OutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

interface SpringDataOutboxRepository extends JpaRepository<OutboxEvent, Long> {
    // Note: 같은 message_key에 앞선 PENDING 행이 있으면 집지 않는다. 임대 중인 행도 PENDING이라 뒤 행을 막아
    // 키별 순서가 유지된다. SKIP LOCKED는 다른 릴레이가 잠근 행을 기다리지 않고 건너뛴다.
    @Query(nativeQuery = true, value = """
            SELECT o.* FROM outbox o
            WHERE o.status = 'PENDING' AND o.next_attempt_at <= :now
              AND (o.claimed_until IS NULL OR o.claimed_until <= :now)
              AND NOT EXISTS (SELECT 1 FROM outbox earlier
                              WHERE earlier.message_key = o.message_key AND earlier.status = 'PENDING'
                                AND earlier.id < o.id)
            ORDER BY o.id
            LIMIT :batchSize
            FOR UPDATE OF o SKIP LOCKED
            """)
    List<OutboxEvent> lockPending(@Param("now") Instant now, @Param("batchSize") int batchSize);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(nativeQuery = true, value = "UPDATE outbox SET claimed_until = :until WHERE id IN (:ids)")
    int lease(@Param("ids") Collection<Long> ids, @Param("until") Instant until);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(nativeQuery = true, value = """
            UPDATE outbox SET status = 'SENT', sent_at = :sentAt, claimed_until = NULL
            WHERE id = :id AND status = 'PENDING'
            """)
    int markSent(@Param("id") Long id, @Param("sentAt") Instant sentAt);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(nativeQuery = true, value = """
            UPDATE outbox SET attempts = attempts + 1, last_error = :error
            WHERE id = :id AND status = 'PENDING'
            """)
    int recordFailure(@Param("id") Long id, @Param("error") String error);
}
