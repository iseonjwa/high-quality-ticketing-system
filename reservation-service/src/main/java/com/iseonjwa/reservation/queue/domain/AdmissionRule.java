package com.iseonjwa.reservation.queue.domain;

// 입장 판정과 관련된 규칙 모음
public final class AdmissionRule {

    private AdmissionRule() {}


    // 순번 조회 만들고 다시 확인해볼 것
    public static AdmissionStatus admissionStatus(boolean tokenIssued, Long seq, Long admitted) {
        if(tokenIssued) return AdmissionStatus.READY;
        return seq <= admitted ? AdmissionStatus.READY : AdmissionStatus.WAITING;
    }

    public static long position(long seq, long admitted) {
        return Math.max(0L, seq - admitted);
    }
}
