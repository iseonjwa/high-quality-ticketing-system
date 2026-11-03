package com.iseonjwa.payment.reconciliation.application;

import com.iseonjwa.payment.reconciliation.domain.MismatchKey;
import com.iseonjwa.payment.reconciliation.domain.MismatchRepository;
import com.iseonjwa.payment.reconciliation.domain.ReconciliationPolicy;
import com.iseonjwa.payment.reconciliation.domain.ReconciliationRule;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashSet;
import java.util.stream.Collectors;

/**
 * 규칙 하나를 판단하고 결과를 한 트랜잭션으로 기록한다(ADR 0029 결정 5). 찾은 불일치는 {@code OPEN}으로 만들거나 이어
 * 갱신하고, 이번에 끝까지 판단했는데 다시 나오지 않은 {@code OPEN} 행은 {@code RESOLVED}로 닫는다. 결제·예매 데이터는
 * 고치지 않는다.
 *
 * <p>Note: 닫는 대상은 "이전에 OPEN이었고, 이번에 찾지 못했고, 판단을 보류하지 않은" 키다. 허용 지연 안이라 판단하지 않은
 * 대상은 불일치가 사라진 것이 아니므로 기록도 닫기도 하지 않는다. 서비스를 거치지 않고 따로 둔 이유는 {@code @Transactional}이
 * 다른 빈의 호출에서만 적용돼, 규칙마다 트랜잭션을 나누려면 서비스 밖에 있어야 해서다.
 */
@Service
public class ReconciliationRecorder {
    private final MismatchRepository mismatches;

    public ReconciliationRecorder(MismatchRepository mismatches) {
        this.mismatches = mismatches;
    }

    @Transactional
    public RuleResult reconcile(ReconciliationRule rule, Instant now, ReconciliationPolicy policy) {
        var evaluation = rule.evaluate(now, policy);
        var open = mismatches.findOpenKeys(rule.code());
        var foundKeys = evaluation.found().stream().map(finding -> finding.key()).collect(Collectors.toSet());
        var toClose = new HashSet<MismatchKey>(open);
        toClose.removeAll(foundKeys);
        toClose.removeAll(evaluation.undecided());

        mismatches.upsertOpen(rule.code(), evaluation.found(), now);
        var resolved = mismatches.resolve(rule.code(), toClose, now);
        return new RuleResult(rule.code().code(), evaluation.found().size(), resolved, evaluation.undecided().size());
    }

    /** 한 규칙의 한 회차 결과. 로그와 테스트용이다. */
    public record RuleResult(String rule, int found, int resolved, int undecided) {}
}
