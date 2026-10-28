package com.iseonjwa.common.messaging.inbox;

import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

public class ProcessedEventRepository {
    private final JdbcTemplate jdbc;

    public ProcessedEventRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 처리한 이벤트로 기록한다. 이미 기록돼 있으면 false다. 호출한 트랜잭션에 참여한다.
     *
     * <p>Note: 확인 뒤 삽입이 아니라 삽입을 먼저 시도한다. 동시에 같은 이벤트가 와도 기본키가 한 쪽만 통과시키고,
     * 다른 쪽은 앞 트랜잭션의 커밋을 기다렸다가 false를 받는다(앞이 롤백되면 true).
     */
    public boolean markProcessed(UUID eventId, String consumerGroup, Instant processedAt) {
        return jdbc.update("""
                INSERT INTO processed_events (event_id, consumer_group, processed_at) VALUES (?, ?, ?)
                ON CONFLICT (event_id, consumer_group) DO NOTHING
                """, eventId, consumerGroup, Timestamp.from(processedAt)) == 1;
    }
}
