package com.iseonjwa.common.messaging.retention;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;

/**
 * 보관 기간이 지난 {@code outbox_events}(SENT)와 {@code processed_events}를 한 배치씩 지운다(ADR 0022 결정 4~6).
 * 호출 한 번이 배치 하나, 트랜잭션 하나다. 배치를 반복하고 사이에 쉬는 일은 부르는 쪽(작업 스케줄러)이 한다.
 *
 * <p>Note: 이 클래스는 빈으로 자동 등록되지 않는다({@code CommonOutboxConfiguration}에 없다). 정리가 필요한 서비스가
 * 직접 등록하고 부른다. 공통 설정에 넣으면 Outbox를 쓰기만 하는 다른 서비스에도 정리가 저절로 켜진다.
 *
 * <p>Note: 중복 실행은 {@code pg_try_advisory_xact_lock}으로 막는다. 같은 작업을 두 인스턴스가 동시에 돌려도 한 쪽만
 * 배치를 지우고 다른 쪽은 0을 받는다. 트랜잭션 잠금이라 커밋이나 롤백 때 저절로 풀려 연결을 계속 붙잡을 필요가 없다.
 * ADR 0022가 말한 세션 잠금({@code pg_try_advisory_lock})은 같은 연결을 끝까지 쥐고 있어야 해서 풀과 함께 쓰기 번거롭다.
 */
public class RetentionCleanup {
    /** {@code outbox_events} 정리 잠금 키. 작업마다 서로 달라야 한다. */
    public static final long OUTBOX_LOCK_KEY = 146_001L;
    /** {@code processed_events} 정리 잠금 키 */
    public static final long PROCESSED_EVENTS_LOCK_KEY = 146_002L;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    public RetentionCleanup(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    /**
     * {@code SENT}이고 {@code sent_at}이 {@code cutoff}보다 앞선 행을 최대 {@code batchSize}건 지운다. PENDING(재시도 대기
     * 포함)과 FAILED는 지우지 않는다.
     *
     * @return 지운 행 수. 다른 실행이 잠금을 쥐고 있으면 0
     */
    public int deleteSentOutbox(Instant cutoff, int batchSize) {
        // 서브쿼리는 idx_outbox_events_sent_at(부분 인덱스)로 오래된 순서를 바로 읽는다
        return deleteBatch(OUTBOX_LOCK_KEY, """
                DELETE FROM outbox_events WHERE id IN (
                    SELECT id FROM outbox_events WHERE status = 'SENT' AND sent_at < ? ORDER BY sent_at LIMIT ?)
                """, Timestamp.from(cutoff), batchSize);
    }

    /** {@code processed_at}이 {@code cutoff}보다 앞선 처리 이력을 최대 {@code batchSize}건 지운다. 지운 행 수를 돌려준다. */
    public int deleteProcessedEvents(Instant cutoff, int batchSize) {
        return deleteBatch(PROCESSED_EVENTS_LOCK_KEY, """
                DELETE FROM processed_events WHERE (event_id, consumer_group) IN (
                    SELECT event_id, consumer_group FROM processed_events WHERE processed_at < ?
                    ORDER BY processed_at LIMIT ?)
                """, Timestamp.from(cutoff), batchSize);
    }

    /**
     * 트랜잭션 하나 안에서 {@code lockKey}의 어드바이저리 잠금을 잡고 {@code deleteSql}을 실행한다. 잠금을 못 잡으면 지우지
     * 않고 0을 돌려준다. 서비스 고유의 정리(예: 결제의 멱등 기록)가 같은 방식을 쓰도록 공개한다.
     */
    public int deleteBatch(long lockKey, String deleteSql, Object... arguments) {
        var deleted = transaction.execute(status -> {
            if (!Boolean.TRUE.equals(jdbc.queryForObject("SELECT pg_try_advisory_xact_lock(?)", Boolean.class, lockKey))) {
                return 0;
            }
            return jdbc.update(deleteSql, arguments);
        });
        return deleted == null ? 0 : deleted;
    }
}
