package com.iseonjwa.payment.reconciliation.domain;

import java.time.Instant;
import java.util.Set;

/**
 * 대사 규칙 하나. 규칙은 읽기만 하고 기록은 {@code ReconciliationRecorder}가 한다. 규칙은 준비(트랜잭션 밖)와 판단(잠금을 잡은
 * 트랜잭션 안) 두 단계다. 이 인터페이스를 구현한 빈을 더하면 작업이 저절로 돌린다.
 */
public interface ReconciliationRule {
    RuleCode code();

    /**
     * 판단에 필요한 외부 입력을 트랜잭션 밖에서 미리 준비한다. 예매 서비스를 부르는 규칙이 여기서 스냅숏을 가져온다. 실패하면
     * 예외를 던지고, 그 규칙은 기록도 닫기도 하지 않고 실패한다. 결제 DB만 보는 규칙은 준비할 것이 없다.
     */
    default Preparation prepare(ReconciliationRound round) {
        return Preparation.NONE;
    }

    /**
     * 이번 회차의 판단 결과를 돌려준다. 잠금을 잡은 트랜잭션 안에서 불리므로 원격 호출을 하지 않고, 결제 DB를 다시 읽어
     * {@code preparation}과 비교한다. 판단 대상은 기간 안의 대상과 이 규칙의 {@code OPEN} 행이 가리키는 대상이다. 허용 지연이
     * 지나지 않은 대상과 준비 때 없던 새 대상은 판단하지 않는다.
     */
    Evaluation evaluate(Instant now, ReconciliationPolicy policy, Preparation preparation);

    /**
     * @param found     불일치가 맞는 대상
     * @param undecided 허용 지연 안이라 이번에는 판단하지 않은 대상의 키. 이 키의 {@code OPEN} 행은 닫지 않는다
     */
    record Evaluation(java.util.List<Finding> found, Set<MismatchKey> undecided) {}
}
