package com.iseonjwa.payment.reconciliation.domain;

/**
 * 규칙이 트랜잭션 밖에서 미리 준비한 입력. 지금은 예매 서비스에서 가져온 스냅숏뿐이다. 결제 DB만 보는 규칙(RC-7·RC-8)은
 * {@link #NONE}을 쓴다.
 *
 * @param reservations 예매 스냅숏 묶음. 예매 서비스와 대조하지 않는 규칙이면 null
 */
public record Preparation(ReservationView reservations) {
    public static final Preparation NONE = new Preparation(null);
}
