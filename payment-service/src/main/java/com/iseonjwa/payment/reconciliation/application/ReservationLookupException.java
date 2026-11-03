package com.iseonjwa.payment.reconciliation.application;

/** 예매 조회가 실패했다. 이번 회차의 그 규칙은 기록도 닫기도 하지 않고 실패한다. */
public class ReservationLookupException extends RuntimeException {
    public ReservationLookupException(String message) {
        super(message);
    }

    public ReservationLookupException(String message, Throwable cause) {
        super(message, cause);
    }
}
