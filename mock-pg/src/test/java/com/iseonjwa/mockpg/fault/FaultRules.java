package com.iseonjwa.mockpg.fault;

/**
 * 테스트에서 장애 설정을 짧게 만드는 도우미.
 */
public final class FaultRules {
    private FaultRules() {
    }

    public static FaultRule of(FaultTarget target, FaultMode mode, Integer count) {
        return new FaultRule(target, mode, null, null, count, null);
    }

    public static FaultRule delay(FaultTarget target, long delayMs, Integer count) {
        return new FaultRule(target, FaultMode.DELAY, delayMs, null, count, null);
    }

    public static FaultRule forOrder(FaultTarget target, FaultMode mode, Integer count, String orderId) {
        return new FaultRule(target, mode, null, null, count, orderId);
    }

    public static FaultRule off(FaultTarget target) {
        return of(target, FaultMode.NONE, null);
    }
}
