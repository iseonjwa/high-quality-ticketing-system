package com.iseonjwa.reservation.reservation.domain.entity;

// OPEN은 사람이 확인할 건, RESOLVED는 DLQ 처리기가 다시 처리해 닫은 건이다
public enum DeadLetterStatus {
    OPEN,
    RESOLVED
}
