package com.iseonjwa.reservation.reservation.domain.entity;

// HELD·CONFIRMED는 판매 중으로 보고 부분 유니크 인덱스가 좌석당 한 건만 허용한다(D2)
public enum ReservationSeatStatus {
    HELD,
    CONFIRMED,
    RELEASED
}
