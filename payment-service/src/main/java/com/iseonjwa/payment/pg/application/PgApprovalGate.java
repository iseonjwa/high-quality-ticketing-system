package com.iseonjwa.payment.pg.application;

import com.iseonjwa.payment.payment.domain.PgProvider;

/**
 * 신규 승인 호출의 허가({@code pg-approve} 그룹, 0018). PG마다 그룹을 따로 둔다(MOCK은 {@code pg-approve}, TOSS는
 * {@code pg-approve-toss}). 한 PG가 막혀도 다른 PG로 승인할 수 있어야 폴백이 의미가 있다(0023 11/16 개정).
 * 신규 승인 시도에만 쓰고, 같은 키·기존 결제 응답에는 쓰지 않아 Circuit Breaker가 OPEN이어도 그 응답은 그대로 나간다.
 */
public interface PgApprovalGate {
    /** @throws PgUnavailableException 그 PG의 CB가 OPEN이거나 동시 호출 한도를 넘으면 */
    PgPermit acquire(PgProvider provider);

    /**
     * 그 PG가 신규 승인을 받을 수 있는지. CB가 OPEN·FORCED_OPEN이 아니면 true다. HALF_OPEN은 시험 호출을 받아야 닫히므로 true다.
     * 동시 호출 한도(Bulkhead)는 보지 않는다. 순간 포화는 요청마다 달라 안내 기준으로 삼지 않는다.
     */
    boolean accepting(PgProvider provider);
}
