package com.iseonjwa.reservation.concert.domain.entity;

// 상태 전이는 조건부 UPDATE로만 한다. 선점(AVAILABLE → HELD)과 만료(HELD → AVAILABLE)는 좌석 선점 모듈이(SA 12-7),
// 결제 결과(HELD → SOLD, 거절 시 HELD → AVAILABLE)는 결제 결과 소비자가 맡는다(SA 8-3).
public enum ScheduleSeatStatus {
    AVAILABLE, HELD, SOLD
}
