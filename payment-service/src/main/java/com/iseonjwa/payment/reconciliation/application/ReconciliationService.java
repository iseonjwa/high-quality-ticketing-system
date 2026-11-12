package com.iseonjwa.payment.reconciliation.application;

import com.iseonjwa.payment.reconciliation.domain.ReconciliationPolicy;
import com.iseonjwa.payment.reconciliation.domain.ReconciliationRound;
import com.iseonjwa.payment.reconciliation.domain.ReconciliationRule;
import com.iseonjwa.payment.reconciliation.domain.ReservationView;
import org.springframework.beans.factory.ObjectProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * 대사 한 회차를 실행한다. 등록된 규칙을 차례로 돌리고 규칙마다 따로 기록한다. 한 규칙이 실패해도 나머지는 돌고, 실패가
 * 있었으면 마지막에 예외를 던져 작업 실패 지표가 오르게 한다.
 *
 * <p>규칙마다 준비(트랜잭션 밖) → 판단·기록(잠금을 잡은 한 트랜잭션) 순서다. 회차 시작 때 {@link ReconciliationRound}를 하나
 * 만들어 모든 규칙의 준비에 넘기고, 예매 스냅숏은 그 안에서 처음 필요할 때 한 번만 가져온다. 준비가 실패한 규칙은 기록도
 * 닫기도 없이 실패한다.
 *
 * <p>Note: 기준 시각을 인자로 받는다. 테스트가 {@code sleep} 없이 허용 지연을 넘기고, 작업은 시계에서 읽은 지금을 넘긴다.
 * 규칙 빈을 더하면 이 서비스가 저절로 돌린다. 예매 조회가 꺼져 있으면(RC-1~RC-6 규칙 빈이 없으면) 예매 서비스를 부르지 않는다.
 */
@Service
public class ReconciliationService {
    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);

    private final List<ReconciliationRule> rules;
    private final ReconciliationRecorder recorder;
    private final ReconciliationProperties properties;
    private final ObjectProvider<ReservationViewLoader> reservationLoader;

    public ReconciliationService(List<ReconciliationRule> rules, ReconciliationRecorder recorder,
            ReconciliationProperties properties, ObjectProvider<ReservationViewLoader> reservationLoader) {
        this.rules = rules.stream().sorted(java.util.Comparator.comparing(rule -> rule.code().ordinal())).toList();
        this.recorder = recorder;
        this.properties = properties;
        this.reservationLoader = reservationLoader;
    }

    /** @return 규칙별 결과. 하나라도 실패하면 나머지를 돌린 뒤 {@link ReconciliationFailedException}을 던진다 */
    public List<ReconciliationRecorder.RuleResult> runOnce(Instant now) {
        var results = new ArrayList<ReconciliationRecorder.RuleResult>();
        var failures = new ArrayList<RuntimeException>();
        var policy = properties.policy();
        var round = new ReconciliationRound(now, policy, reservationSnapshots(now, policy));
        for (var rule : rules) {
            try {
                // 준비는 트랜잭션 밖이다. 실패하면 기록도 닫기도 하지 않고 이 규칙만 실패한다
                var preparation = rule.prepare(round);
                var result = recorder.reconcile(rule, preparation, now, policy);
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
        commitProgress(round, results, failures);
        if (!failures.isEmpty()) {
            var failure = new ReconciliationFailedException(failures.size() + "개 규칙이 실패했다", failures.get(0));
            failures.stream().skip(1).forEach(failure::addSuppressed);
            throw failure;
        }
        return results;
    }

    /** 규칙이 모두 판단을 마쳤을 때만 변경 목록 진행을 옮긴다. 실패나 건너뜀이 있으면 다음 회차가 같은 구간을 다시 읽는다. */
    private void commitProgress(ReconciliationRound round, List<ReconciliationRecorder.RuleResult> results,
            List<RuntimeException> failures) {
        var view = round.loadedReservations();
        if (view == null || view.next() == null || !failures.isEmpty()) return;
        if (results.stream().anyMatch(ReconciliationRecorder.RuleResult::skipped)) return;
        var loader = reservationLoader.getIfAvailable();
        if (loader != null) loader.commit(view.next());
    }

    private Supplier<ReservationView> reservationSnapshots(Instant now, ReconciliationPolicy policy) {
        return () -> {
            var loader = reservationLoader.getIfAvailable();
            if (loader == null) throw new IllegalStateException("예매 조회가 꺼져 있다(payment.reconciliation.reservation.enabled)");
            return loader.load(now, policy);
        };
    }

    public static class ReconciliationFailedException extends RuntimeException {
        ReconciliationFailedException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
