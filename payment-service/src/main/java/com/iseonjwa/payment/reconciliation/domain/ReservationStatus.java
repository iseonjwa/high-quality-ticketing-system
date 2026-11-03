package com.iseonjwa.payment.reconciliation.domain;

/** 예매 서비스의 대사용 조회 API가 돌려주는 예매 상태(ADR 0029 결정 2). 알 수 없는 값은 조회 실패로 본다. */
public enum ReservationStatus {
    HELD, CONFIRMED, EXPIRED, RELEASED, CANCELED
}
