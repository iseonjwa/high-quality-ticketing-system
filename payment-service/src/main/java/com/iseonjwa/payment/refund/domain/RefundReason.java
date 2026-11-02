package com.iseonjwa.payment.refund.domain;

import java.util.Optional;

/**
 * 환불을 시작한 이유(domain-and-states.md 3절). {@code PG_AMOUNT_MISMATCH}는 Ops 명령만 쓰고 이벤트로는 받지 않는다.
 */
public enum RefundReason {
    EXPIRED_AFTER_APPROVAL(true),
    CANCELED(true),
    DUPLICATE_APPROVAL(true),
    NOT_CONFIRMABLE(true),
    PG_AMOUNT_MISMATCH(false);

    private final boolean requestableByEvent;

    RefundReason(boolean requestableByEvent) {
        this.requestableByEvent = requestableByEvent;
    }

    public boolean isRequestableByEvent() {
        return requestableByEvent;
    }

    /** {@code RefundRequested} 이벤트가 보낼 수 있는 사유만 돌려준다. 모르는 값과 Ops 전용 값은 비어 있다. */
    public static Optional<RefundReason> fromEvent(String value) {
        for (var reason : values()) {
            if (reason.requestableByEvent && reason.name().equals(value)) {
                return Optional.of(reason);
            }
        }
        return Optional.empty();
    }
}
