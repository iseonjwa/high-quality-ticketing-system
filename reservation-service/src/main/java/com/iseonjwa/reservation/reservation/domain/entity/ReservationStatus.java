package com.iseonjwa.reservation.reservation.domain.entity;

// 거절은 예매 행을 만들지 않고 seat_hold_requests에 남기므로 REJECTED가 없다(SA 12-3, #13)
public enum ReservationStatus {
    HELD,
    CONFIRMED,
    EXPIRED,
    RELEASED,
    CANCELED
}
