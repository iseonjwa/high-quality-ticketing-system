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
 * {@code outbox_events} 테이블 접근. 엔티티 없이 JDBC로 쓰므로 서비스가 엔티티 스캔을 따로 설정할 필요가 없다.
 * 테이블은 서비스의 Flyway가 만든다(표준 DDL은 docs/guides/common-modules.md).
 *
 * <p>Note: {@code insert}는 호출한 트랜잭션에 참여한다. JPA 트랜잭션 안에서 같은 연결을 쓰므로 업무 변경과 함께
 * 커밋되거나 롤백된다. 키별 순서는 같은 키의 쓰기가 업무 행 잠금으로 줄을 서서 id 순서가 커밋 순서와 같다는
 * 전제에 기댄다. 업무 행을 잠그지 않고 같은 키로 쓰는 경로를 만들면 이 전제가 깨진다.
 */
public class OutboxRepository {
    /** attempts 컬럼(SMALLINT)의 상한 */
    public static final int ATTEMPTS_MAX = 32767;
    private static final RowMapper<OutboxMessage> MESSAGE = (rs, rowNum) -> new OutboxMessage(rs.getLong("id"),
            rs.getObject("event_id", UUID.class), rs.getString("topic"), rs.getString("message_key"),
            rs.getString("event_type"), rs.getString("payload"), rs.getInt("attempts"));

    private final JdbcTemplate jdbc;

    public OutboxRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(UUID eventId, String topic, String messageKey, String eventType, String payload, Instant now) {
        jdbc.update("""
                INSERT INTO outbox_events (event_id, topic, message_key, event_type, payload, status, attempts,
                                    next_attempt_at, created_at)
                VALUES (?, ?, ?, ?, ?::jsonb, 'PENDING', 0, ?, ?)
                """, eventId, topic, messageKey, eventType, payload, Timestamp.from(now), Timestamp.from(now));
    }

    /**
     * 발행할 행을 집어 임대한다. 한 문장으로 집기와 임대를 함께 해 별도 트랜잭션이 필요 없다.
     * 서브쿼리는 ARRAY(...)로 감싸 한 번만 실행되게 고정한다(IN 서브쿼리는 계획에 따라 다시 실행될 수 있다).
     * 같은 키에 앞선 PENDING이나 FAILED 행이 있으면 집지 않아 키별 순서를 지키고(임대 중인 행도 PENDING이라 뒤 행을
     * 막는다), 다른 릴레이가 잠근 행은 기다리지 않고 건너뛴다. 임대가 끝나면 다시 집힌다(중복 전송, 소비자가 멱등).
     *
     * <p>Note: FAILED가 같은 키의 뒤 행을 막는 것은 의도다. 앞선 이벤트를 건너뛰고 뒤 이벤트를 먼저 내보내면 순서가
     * 뒤집히므로, 운영자가 Ops {@code outbox requeue}로 되살릴 때까지 그 키는 멈춰 있다(ADR 0022). PENDING과 FAILED를
     * {@code status <> 'SENT'} 하나로 합치지 않고 NOT EXISTS를 둘로 나눈 것은 상태별 부분 인덱스
     * ({@code message_key, id})를 각각 타게 하려는 것이다. 합치면 어느 부분 인덱스도 쓰지 못한다.
     */
    public List<OutboxMessage> claimNext(Instant now, Instant leaseUntil, int batchSize) {
        var claimed = jdbc.query("""
                UPDATE outbox_events SET claimed_until = ?
                WHERE id = ANY(ARRAY(
                    SELECT o.id FROM outbox_events o
                    WHERE o.status = 'PENDING' AND o.next_attempt_at <= ?
                      AND (o.claimed_until IS NULL OR o.claimed_until <= ?)
                      AND NOT EXISTS (SELECT 1 FROM outbox_events earlier
                                      WHERE earlier.message_key = o.message_key AND earlier.status = 'PENDING'
                                        AND earlier.id < o.id)
                      AND NOT EXISTS (SELECT 1 FROM outbox_events failed
                                      WHERE failed.message_key = o.message_key AND failed.status = 'FAILED'
                                        AND failed.id < o.id)
                    ORDER BY o.id
                    LIMIT ?
                    FOR UPDATE OF o SKIP LOCKED))
                RETURNING id, event_id, topic, message_key, event_type, payload::text AS payload, attempts
                """, MESSAGE, Timestamp.from(leaseUntil), Timestamp.from(now), Timestamp.from(now), batchSize);
        // RETURNING의 행 순서는 보장되지 않는다
        return claimed.stream().sorted(Comparator.comparingLong(OutboxMessage::id)).toList();
    }

    // 아직 PENDING일 때만 SENT로 바꾼다. 반환값은 실제로 바뀌었는지다.
    public boolean markSent(long id, Instant sentAt) {
        return jdbc.update("""
                UPDATE outbox_events SET status = 'SENT', sent_at = ?, claimed_until = NULL
                WHERE id = ? AND status = 'PENDING'
                """, Timestamp.from(sentAt), id) == 1;
    }

    /**
     * 일시 오류를 기록한다. 실패 횟수를 올리고, 임대를 바로 풀어 {@code nextAttemptAt}이 다음 시도 시각을 정하게 한다.
     *
     * <p>Note: 임대를 남겨 두면 임대 만료(30초)가 사실상의 재시도 간격이 되어 백오프가 무의미해진다. SMALLINT 상한을
     * 넘기면 갱신 자체가 실패해 같은 배치의 다른 행이 계속 늦어지므로 상한에서 멈춘다.
     *
     * @return 행이 아직 PENDING이라 실제로 바뀌었는지
     */
    public boolean recordFailure(long id, String error, Instant nextAttemptAt) {
        return jdbc.update("""
                UPDATE outbox_events SET attempts = LEAST(attempts + 1, ?), last_error = ?, next_attempt_at = ?,
                                         claimed_until = NULL
                WHERE id = ? AND status = 'PENDING'
                """, ATTEMPTS_MAX, error, Timestamp.from(nextAttemptAt), id) == 1;
    }

    /** 영구 오류를 기록한다. FAILED가 되면 더 집지 않는다. 복구는 Ops {@code outbox requeue}다(ISE-42). */
    public boolean markFailed(long id, String error) {
        return jdbc.update("""
                UPDATE outbox_events SET status = 'FAILED', last_error = ?, claimed_until = NULL
                WHERE id = ? AND status = 'PENDING'
                """, error, id) == 1;
    }

    /**
     * 이번 실행에서 보내지 못한 행의 임대를 바로 푼다. 임대가 끝나길 기다리지 않고 다음 실행이 집게 한다.
     *
     * <p>Note: 내가 건 임대(leaseUntil)인 행만 푼다. 실행이 길어져 임대가 끝난 뒤 다른 릴레이가 다시 집었다면
     * 그 새 임대를 지우지 않는다.
     */
    public void release(Collection<Long> ids, Instant leaseUntil) {
        if (ids.isEmpty()) return;
        jdbc.update(connection -> {
            var statement = connection.prepareStatement("""
                    UPDATE outbox_events SET claimed_until = NULL
                    WHERE id = ANY(?) AND status = 'PENDING' AND claimed_until = ?
                    """);
            statement.setArray(1, connection.createArrayOf("bigint", ids.toArray()));
            statement.setTimestamp(2, Timestamp.from(leaseUntil));
            return statement;
        });
    }
}
