package com.iseonjwa.mockpg.fault;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 호출 종류별 장애 설정을 들고, 요청마다 적용할 장애를 정한다(mock-pg.md 5절).
 * 설정 API가 열리지 않는 프로필에서는 모든 호출이 NONE이다.
 */
@Component
public class FaultInjector {
    private final Map<FaultTarget, AtomicReference<FaultRule>> rules = new EnumMap<>(FaultTarget.class);

    public FaultInjector() {
        for (var target : FaultTarget.values()) {
            rules.put(target, new AtomicReference<>(FaultRule.none(target)));
        }
    }

    public void apply(FaultRule rule) {
        rules.get(rule.target()).set(rule);
    }

    public List<FaultRule> currentRules() {
        var current = new ArrayList<FaultRule>();
        for (var rule : rules.values()) {
            current.add(rule.get());
        }
        return current;
    }

    /**
     * 요청 하나에 적용할 장애를 정한다. 요청이 들어올 때 한 번 부르고, 그 요청이 끝날 때까지 돌려받은 값을 쓴다.
     * 순서는 호출 종류 일치 → {@code orderId} 일치 → {@code rate} 통과 → 횟수 소비다. 일치하지 않거나 확률에서 떨어진
     * 요청은 횟수를 쓰지 않고 {@link Fault#NONE}이다.
     *
     * <p>Note: 횟수가 정해진 설정은 compare-and-set으로 한 번씩 소비한다. 동시에 들어온 두 요청이 같은 한 번을
     * 나눠 쓰지 않고, 그 사이 설정이 바뀌었으면 옛 설정의 남은 횟수가 새 설정을 덮어쓰지 않는다.
     */
    public Fault claim(FaultTarget target, String orderId) {
        var current = rules.get(target);
        while (true) {
            var rule = current.get();
            if (!rule.isActive() || !rule.matches(orderId)) return Fault.NONE;
            if (rule.rate() < 1.0 && ThreadLocalRandom.current().nextDouble() >= rule.rate()) return Fault.NONE;
            if (current.compareAndSet(rule, rule.afterOneUse())) {
                return new Fault(rule.mode(), rule.delayMs() == null ? 0 : rule.delayMs());
            }
        }
    }
}
