package com.iseonjwa.payment.pg.application;

/**
 * PG 호출 허가를 얻지 못했다(Circuit Breaker OPEN 또는 Bulkhead 포화). 아직 PG를 부르지 않았고 아무것도 저장하지 않았다.
 *
 * <p>Note: OPEN 중에는 거절마다 이 예외가 생기므로 스택 트레이스를 만들지 않는다. Bulkhead 포화는 DB(TX1)가 느려서 자리가
 * 오래 잡힌 경우에도 생기므로, 503이 항상 PG 장애를 뜻하지는 않는다.
 */
public class PgUnavailableException extends RuntimeException {
    public PgUnavailableException(String message) {
        super(message, null, false, false);
    }
}
