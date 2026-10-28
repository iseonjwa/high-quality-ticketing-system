package com.iseonjwa.payment.pg.application;

/**
 * 신규 승인 호출의 허가({@code pg-approve} 그룹, 0018). 신규 승인 시도에만 쓰고, 같은 키·기존 결제 응답에는 쓰지 않아
 * Circuit Breaker가 OPEN이어도 그 응답은 그대로 나간다.
 */
public interface PgApprovalGate {
    /** @throws PgUnavailableException CB가 OPEN이거나 동시 호출 한도를 넘으면 */
    PgPermit acquire();
}
