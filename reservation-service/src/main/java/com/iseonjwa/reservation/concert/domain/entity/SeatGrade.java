package com.iseonjwa.reservation.concert.domain.entity;

// LOTTERY는 추첨 설정 때 회차 좌석 일부를 원래 등급에서 떼어 표시하는 값이다(추첨 D9).
// 공연장 좌석 배치와 회차 좌석 가격표에는 쓰지 않고, 일반 선점은 이 등급의 좌석을 잡지 않는다.
public enum SeatGrade {
    VIP, R, S, LOTTERY
}
