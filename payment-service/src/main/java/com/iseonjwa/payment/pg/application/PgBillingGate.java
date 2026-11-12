package com.iseonjwa.payment.pg.application;

/**
 * 추첨 자동결제 승인 호출의 허가({@code pg-billing} 그룹, ADR 0051). 사용자가 기다리지 않는 배경 호출이라 일반 결제의
 * {@code pg-approve} 허가와 자리를 나눠, 서로의 자리를 빼앗지 않게 한다. 신규 자동결제 시도에만 쓴다.
 */
public interface PgBillingGate {
    /** @throws PgUnavailableException CB가 OPEN이거나 동시 호출 한도를 넘으면. 소비자가 같은 레코드를 다시 읽는다 */
    PgPermit acquire();
}
