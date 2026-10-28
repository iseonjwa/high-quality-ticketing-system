package com.iseonjwa.payment.payment.domain;

import java.util.Set;

/**
 * 결제 상태와 허용된 전이. 전이표는 docs/design/payment/domain-and-states.md 1절을 따른다.
 */
public enum PaymentStatus {
    REQUESTED,
    UNKNOWN,
    APPROVED,
    NEEDS_MANUAL_CHECK,
    DECLINED,
    REFUNDED;

    public boolean canTransitionTo(PaymentStatus next) {
        return allowedNext().contains(next);
    }

    public void requireTransitionTo(PaymentStatus next) {
        if (!canTransitionTo(next)) {
            throw new IllegalStateException("허용되지 않는 결제 상태 전이입니다: " + this + " -> " + next);
        }
    }

    // 재확인 스케줄러가 집어 가야 하는 미확정 상태. next_check_at이 반드시 있어야 한다(INV-4·INV-8).
    public boolean awaitsRecheck() {
        return this == REQUESTED || this == UNKNOWN;
    }

    private Set<PaymentStatus> allowedNext() {
        return switch (this) {
            case REQUESTED -> Set.of(APPROVED, DECLINED, UNKNOWN, NEEDS_MANUAL_CHECK);
            case UNKNOWN -> Set.of(APPROVED, DECLINED, NEEDS_MANUAL_CHECK);
            case NEEDS_MANUAL_CHECK -> Set.of(APPROVED, DECLINED, REFUNDED);
            case APPROVED -> Set.of(REFUNDED);
            case DECLINED, REFUNDED -> Set.of();
        };
    }
}
