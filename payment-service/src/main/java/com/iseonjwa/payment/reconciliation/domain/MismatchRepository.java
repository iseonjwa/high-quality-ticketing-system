package com.iseonjwa.payment.reconciliation.domain;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/** 대사 불일치 기록. 쓰기는 호출한 쪽의 트랜잭션에 참여한다. */
public interface MismatchRepository {
    /** 이 규칙의 {@code OPEN} 행 키. */
    Set<MismatchKey> findOpenKeys(RuleCode rule);

    /** 새 불일치는 {@code OPEN}으로 만들고, 같은 키의 {@code OPEN} 행이 있으면 {@code last_seen_at}과 상세만 갱신한다. */
    void upsertOpen(RuleCode rule, List<Finding> findings, Instant now);

    /** 키의 {@code OPEN} 행을 {@code RESOLVED}로 닫는다. @return 닫은 행 수 */
    int resolve(RuleCode rule, Collection<MismatchKey> keys, Instant now);
}
