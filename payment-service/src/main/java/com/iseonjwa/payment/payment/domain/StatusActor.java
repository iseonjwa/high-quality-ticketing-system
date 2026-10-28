package com.iseonjwa.payment.payment.domain;

/**
 * 상태 이력의 행위자. 시스템 작업은 SYSTEM, 운영자 명령은 OPS:&lt;operator&gt;로 남긴다.
 */
public record StatusActor(String value) {
    public static final StatusActor SYSTEM = new StatusActor("SYSTEM");

    public static StatusActor ops(String operator) {
        return new StatusActor("OPS:" + operator);
    }
}
