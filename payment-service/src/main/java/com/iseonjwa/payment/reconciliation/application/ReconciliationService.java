package com.iseonjwa.payment.reconciliation.application;

import com.iseonjwa.payment.reconciliation.domain.ReconciliationRule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 대사 한 회차를 실행한다. 등록된 규칙을 차례로 돌리고 규칙마다 따로 기록한다. 한 규칙이 실패해도 나머지는 돌고, 실패가
 * 있었으면 마지막에 예외를 던져 작업 실패 지표가 오르게 한다.
 *
 * <p>Note: 기준 시각을 인자로 받는다. 테스트가 {@code sleep} 없이 허용 지연을 넘기고, 작업은 시계에서 읽은 지금을 넘긴다.
 * RC-1~RC-6은 예매 서비스 조회가 필요해 ISE-168에서 규칙으로 더한다. 규칙 빈을 더하면 이 서비스가 저절로 돌린다.
 */
@Service
public class ReconciliationService {
    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);

    private final List<ReconciliationRule> rules;
    private final ReconciliationRecorder recorder;
    private final ReconciliationProperties properties;

    public ReconciliationService(List<ReconciliationRule> rules, ReconciliationRecorder recorder,
            ReconciliationProperties properties) {
        this.rules = rules.stream().sorted(java.util.Comparator.comparing(rule -> rule.code().ordinal())).toList();
        this.recorder = recorder;
        this.properties = properties;
    }

    /** @return 규칙별 결과. 하나라도 실패하면 나머지를 돌린 뒤 {@link ReconciliationFailedException}을 던진다 */
    public List<ReconciliationRecorder.RuleResult> runOnce(Instant now) {
        var results = new ArrayList<ReconciliationRecorder.RuleResult>();
        var failures = new ArrayList<RuntimeException>();
        for (var rule : rules) {
            try {
                var result = recorder.reconcile(rule, now, properties.policy());
                results.add(result);
                if (result.skipped()) {
                    log.info("대사 규칙 건너뜀(다른 인스턴스가 실행 중) rule={}", result.rule());
                    continue;
                }
                log.info("대사 규칙 완료 rule={} found={} resolved={} undecided={}", result.rule(), result.found(),
                        result.resolved(), result.undecided());
            } catch (RuntimeException exception) {
                log.error("대사 규칙 실패 rule={}", rule.code().code(), exception);
                failures.add(exception);
            }
        }
        if (!failures.isEmpty()) {
            var failure = new ReconciliationFailedException(failures.size() + "개 규칙이 실패했다", failures.get(0));
            failures.stream().skip(1).forEach(failure::addSuppressed);
            throw failure;
        }
        return results;
    }

    public static class ReconciliationFailedException extends RuntimeException {
        ReconciliationFailedException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
