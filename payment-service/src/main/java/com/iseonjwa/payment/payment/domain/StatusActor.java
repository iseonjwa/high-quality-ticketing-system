package com.iseonjwa.payment.payment.domain;

/**
 * 상태 이력의 행위자. 시스템 작업은 SYSTEM, 운영자 명령은 OPS:&lt;operator&gt;로 남긴다.
 */
public record StatusActor(String value) {
    // payment_status_history.actor 컬럼 길이
    private static final int MAX_LENGTH = 40;
    public static final StatusActor SYSTEM = new StatusActor("SYSTEM");

    public StatusActor {
        if (value == null || value.isBlank() || value.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("행위자는 1~" + MAX_LENGTH + "자여야 합니다");
        }
    }

    public static StatusActor ops(String operator) {
        if (operator == null || operator.isBlank()) {
            throw new IllegalArgumentException("운영자 식별자가 필요합니다");
        }
        return new StatusActor("OPS:" + operator);
    }
}
