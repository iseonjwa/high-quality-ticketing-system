package com.iseonjwa.reservation.reservation.domain.entity;

// VOID는 예매 취소(ReservationCancelService)가 쓴다
public enum TicketStatus {
    ISSUED,
    VOID
}
