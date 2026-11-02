package com.iseonjwa.mockpg.fault;

import java.util.Objects;

/**
 * 호출 종류 하나의 장애 설정. 불변이고 {@link FaultInjector}가 통째로 갈아 끼운다.
 *
 * @param count 남은 적용 횟수. null이면 해제할 때까지 계속 적용한다(부하 테스트용). 다 쓰면 NONE으로 돌아간다
 */
public record FaultRule(FaultTarget target, FaultMode mode, Integer count) {
    public FaultRule {
        Objects.requireNonNull(target, "target이 필요합니다");
        Objects.requireNonNull(mode, "mode가 필요합니다");
        if (count != null && count < 1) {
            throw new IllegalArgumentException("count는 1 이상이어야 합니다");
        }
    }

    static FaultRule none(FaultTarget target) {
        return new FaultRule(target, FaultMode.NONE, null);
    }

    boolean isActive() {
        return mode != FaultMode.NONE;
    }

    FaultRule afterOneUse() {
        if (count == null) return this;
        return count == 1 ? none(target) : new FaultRule(target, mode, count - 1);
    }
}
