package com.iseonjwa.payment.reconciliation.domain;

import java.time.Instant;
import java.util.Set;

/**
 * 대사 규칙 하나. 규칙은 읽기만 하고 기록은 {@code ReconciliationRecorder}가 한다. 새 규칙(RC-1~RC-6)은 이 인터페이스를
 * 구현한 빈을 더하면 작업이 저절로 돌린다.
 */
public interface ReconciliationRule {
    RuleCode code();

    /**
     * 이번 회차의 판단 결과를 돌려준다. 판단 대상은 기간 안의 대상과 이 규칙의 {@code OPEN} 행이 가리키는 대상이다.
     * 허용 지연이 지나지 않은 대상은 판단하지 않는다.
     */
    Evaluation evaluate(Instant now, ReconciliationPolicy policy);

    /**
     * @param found     불일치가 맞는 대상
     * @param undecided 허용 지연 안이라 이번에는 판단하지 않은 대상의 키. 이 키의 {@code OPEN} 행은 닫지 않는다
     */
    record Evaluation(java.util.List<Finding> found, Set<MismatchKey> undecided) {}
}
