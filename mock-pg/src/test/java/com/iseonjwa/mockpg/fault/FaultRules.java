package com.iseonjwa.mockpg.fault;

/**
 * 테스트에서 장애 설정을 짧게 만드는 도우미.
 */
public final class FaultRules {
    private FaultRules() {
    }

    public static FaultRule of(FaultTarget target, FaultMode mode, Integer count) {
        return rule(target, mode, null, null, count, null);
    }

    public static FaultRule delay(FaultTarget target, long delayMs, Integer count) {
        return rule(target, FaultMode.DELAY, delayMs, null, count, null);
    }

    public static FaultRule forOrder(FaultTarget target, FaultMode mode, Integer count, String orderId) {
        return rule(target, mode, null, null, count, orderId);
    }

    public static FaultRule forRefund(FaultTarget target, FaultMode mode, Integer count, String refundId) {
        return new FaultRule(target, mode, null, null, count, null, refundId);
    }

    /** 주문 한정 규칙을 만든다(환불 식별자 없음). */
    public static FaultRule rule(FaultTarget target, FaultMode mode, Long delayMs, Double rate, Integer count,
            String orderId) {
        return new FaultRule(target, mode, delayMs, rate, count, orderId, null);
    }

    public static FaultRule off(FaultTarget target) {
        return of(target, FaultMode.NONE, null);
    }
}
