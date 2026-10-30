package com.iseonjwa.reservation.concert.domain.entity;

// 상태 전이(AVAILABLE → HELD → SOLD, 만료 시 HELD → AVAILABLE)는 좌석 선점 모듈의 조건부 UPDATE가 맡는다(SA 12-7).
public enum ScheduleSeatStatus {
    AVAILABLE, HELD, SOLD
}
