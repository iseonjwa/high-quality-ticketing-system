package com.iseonjwa.reservation.queue.domain;

// WAITING·READY: 진입·순번 조회 응답 (seq <= admitted 판단 결과)
// ADMITTED: 토큰 API 응답 (토큰을 받음)
public enum AdmissionStatus {
    READY,
    WAITING,
    ADMITTED;

}
