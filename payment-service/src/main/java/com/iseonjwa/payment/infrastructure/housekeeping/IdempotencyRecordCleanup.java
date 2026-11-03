package com.iseonjwa.payment.infrastructure.housekeeping;

import com.iseonjwa.common.messaging.retention.RetentionCleanup;

import java.sql.Timestamp;
import java.time.Instant;

/**
 * 만료된 {@code idempotency_records}를 한 배치씩 지운다(data-model.md 5절, ADR 0022 결정 5). 잠금과 트랜잭션은
 * {@link RetentionCleanup#deleteBatch}가 맡는다.
 *
 * <p>Note: 만료가 지나도 연결된 결제({@code resource_id})가 {@code REQUESTED}·{@code UNKNOWN}·{@code NEEDS_MANUAL_CHECK}
 * 이면 지우지 않는다. 같은 키로 다시 요청이 오면 이 기록으로 기존 결제를 찾아 돌려줘야 하는데, 결과가 정해지지 않은 결제의 기록이
 * 사라지면 같은 요청이 새 결제처럼 보일 수 있다. {@code ORIGIN}과 {@code ALIAS}를 가리지 않는다. 이 조건은 서브쿼리 안에
 * 있어 {@code LIMIT}이 지울 수 있는 행에만 적용된다(못 지우는 행이 많아도 뒤의 지울 행이 굶지 않는다).
 */
public class IdempotencyRecordCleanup {
    /** 정리 잠금 키. {@link RetentionCleanup}의 다른 작업 키와 달라야 한다. */
    static final long LOCK_KEY = 146_003L;

    private final RetentionCleanup cleanup;

    public IdempotencyRecordCleanup(RetentionCleanup cleanup) {
        this.cleanup = cleanup;
    }

    /** @return 지운 행 수. 다른 실행이 잠금을 쥐고 있으면 0 */
    public int deleteExpired(Instant now, int batchSize) {
        return cleanup.deleteBatch(LOCK_KEY, """
                DELETE FROM idempotency_records WHERE (user_id, idem_key) IN (
                    SELECT r.user_id, r.idem_key FROM idempotency_records r
                    WHERE r.expires_at < ?
                      AND NOT EXISTS (SELECT 1 FROM payments p WHERE p.id = r.resource_id
                                      AND p.status IN ('REQUESTED', 'UNKNOWN', 'NEEDS_MANUAL_CHECK'))
                    ORDER BY r.expires_at LIMIT ?)
                """, Timestamp.from(now), batchSize);
    }
}
