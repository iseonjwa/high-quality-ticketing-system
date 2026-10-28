package com.iseonjwa.payment.pg.application;

/**
 * PG 호출 허가를 얻지 못했다(Circuit Breaker OPEN 또는 Bulkhead 포화). 아직 PG를 부르지 않았고 아무것도 저장하지 않았다.
 */
public class PgUnavailableException extends RuntimeException {
    public PgUnavailableException(String message) {
        super(message);
    }
}
