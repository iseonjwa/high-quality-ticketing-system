package com.iseonjwa.payment.reconciliation.domain;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/** 대사 불일치 기록. 쓰기는 호출한 쪽의 트랜잭션에 참여한다. */
public interface MismatchRepository {
    /** 규칙별 상호 배제 잠금 키의 바탕. 정리 작업의 잠금 키(146_001~146_003)와 겹치지 않는다. */
    long LOCK_KEY_BASE = 167_000L;

    static long lockKey(RuleCode rule) {
        return LOCK_KEY_BASE + rule.ordinal();
    }

    /**
     * 이 규칙의 트랜잭션 잠금({@code pg_try_advisory_xact_lock})을 시도한다. 호출한 쪽의 트랜잭션이 끝날 때 저절로 풀린다.
     * @return 잠금을 잡았으면 true. 다른 인스턴스가 쥐고 있으면 false
     */
    boolean tryLock(RuleCode rule);

    /** 이 규칙의 {@code OPEN} 행 키. */
    Set<MismatchKey> findOpenKeys(RuleCode rule);

    /** 새 불일치는 {@code OPEN}으로 만들고, 같은 키의 {@code OPEN} 행이 있으면 {@code last_seen_at}과 상세만 갱신한다. */
    void upsertOpen(RuleCode rule, List<Finding> findings, Instant now);

    /** 키의 {@code OPEN} 행을 {@code RESOLVED}로 닫는다. @return 닫은 행 수 */
    int resolve(RuleCode rule, Collection<MismatchKey> keys, Instant now);
}
