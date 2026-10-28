package com.iseonjwa.common.messaging.outbox;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * {@code outbox} 테이블 접근. 엔티티 없이 JDBC로 쓰므로 서비스가 엔티티 스캔을 따로 설정할 필요가 없다.
 * 테이블은 서비스의 Flyway가 만든다(스키마는 docs/design/payment/data-model.md 6절).
 *
 * <p>Note: {@code insert}는 호출한 트랜잭션에 참여한다. JPA 트랜잭션 안에서 같은 연결을 쓰므로 업무 변경과 함께
 * 커밋되거나 롤백된다. 키별 순서는 같은 키의 쓰기가 업무 행 잠금으로 줄을 서서 id 순서가 커밋 순서와 같다는
 * 전제에 기댄다. 업무 행을 잠그지 않고 같은 키로 쓰는 경로를 만들면 이 전제가 깨진다.
 */
public class OutboxRepository {
    private static final int ATTEMPTS_MAX = 32767;
    private static final RowMapper<OutboxMessage> MESSAGE = (rs, rowNum) -> new OutboxMessage(rs.getLong("id"),
            rs.getObject("event_id", UUID.class), rs.getString("topic"), rs.getString("message_key"),
            rs.getString("event_type"), rs.getString("payload"));

    private final JdbcTemplate jdbc;

    public OutboxRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(UUID eventId, String topic, String messageKey, String eventType, String payload, Instant now) {
        jdbc.update("""
                INSERT INTO outbox (event_id, topic, message_key, event_type, payload, status, attempts,
                                    next_attempt_at, created_at)
                VALUES (?, ?, ?, ?, ?::jsonb, 'PENDING', 0, ?, ?)
                """, eventId, topic, messageKey, eventType, payload, Timestamp.from(now), Timestamp.from(now));
    }

    /**
     * 발행할 행을 집어 임대한다. 한 문장으로 집기와 임대를 함께 해 별도 트랜잭션이 필요 없다.
     * 같은 키에 앞선 PENDING 행이 있으면 집지 않아 키별 순서를 지키고(임대 중인 행도 PENDING이라 뒤 행을 막는다),
     * 다른 릴레이가 잠근 행은 기다리지 않고 건너뛴다. 임대가 끝나면 다시 집힌다(중복 전송, 소비자가 멱등).
     */
    public List<OutboxMessage> claimNext(Instant now, Instant leaseUntil, int batchSize) {
        // TODO: 앞선 행이 FAILED여도 뒤 행을 막도록 조건을 넓힌다(ISE-40, FAILED 전이를 만들 때)
        var claimed = jdbc.query("""
                UPDATE outbox SET claimed_until = ?
                WHERE id IN (
                    SELECT o.id FROM outbox o
                    WHERE o.status = 'PENDING' AND o.next_attempt_at <= ?
                      AND (o.claimed_until IS NULL OR o.claimed_until <= ?)
                      AND NOT EXISTS (SELECT 1 FROM outbox earlier
                                      WHERE earlier.message_key = o.message_key AND earlier.status = 'PENDING'
                                        AND earlier.id < o.id)
                    ORDER BY o.id
                    LIMIT ?
                    FOR UPDATE OF o SKIP LOCKED)
                RETURNING id, event_id, topic, message_key, event_type, payload::text AS payload
                """, MESSAGE, Timestamp.from(leaseUntil), Timestamp.from(now), Timestamp.from(now), batchSize);
        // RETURNING의 행 순서는 보장되지 않는다
        return claimed.stream().sorted(Comparator.comparingLong(OutboxMessage::id)).toList();
    }

    // 아직 PENDING일 때만 SENT로 바꾼다. 반환값은 실제로 바뀌었는지다.
    public boolean markSent(long id, Instant sentAt) {
        return jdbc.update("""
                UPDATE outbox SET status = 'SENT', sent_at = ?, claimed_until = NULL
                WHERE id = ? AND status = 'PENDING'
                """, Timestamp.from(sentAt), id) == 1;
    }

    // 발행 실패를 기록한다. 임대는 그대로 두어 임대가 끝난 뒤 다시 집힌다.
    public void recordFailure(long id, String error) {
        // Note: SMALLINT 상한을 넘기면 갱신 자체가 실패해 같은 배치의 다른 행이 계속 늦어진다
        jdbc.update("""
                UPDATE outbox SET attempts = LEAST(attempts + 1, ?), last_error = ?
                WHERE id = ? AND status = 'PENDING'
                """, ATTEMPTS_MAX, error, id);
    }

    // 이번 실행에서 보내지 못한 행의 임대를 바로 푼다. 임대가 끝나길 기다리지 않고 다음 실행이 집게 한다.
    public void release(Collection<Long> ids) {
        for (var id : ids) {
            jdbc.update("UPDATE outbox SET claimed_until = NULL WHERE id = ? AND status = 'PENDING'", id);
        }
    }
}
