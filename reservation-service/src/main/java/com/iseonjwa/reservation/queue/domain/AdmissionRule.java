package com.iseonjwa.reservation.queue.domain;

// 입장 판정과 관련된 규칙 모음
public final class AdmissionRule {

    private AdmissionRule() {}

    public static long position(long seq, long admitted) {
        return Math.max(0L, seq - admitted);
    }
}
