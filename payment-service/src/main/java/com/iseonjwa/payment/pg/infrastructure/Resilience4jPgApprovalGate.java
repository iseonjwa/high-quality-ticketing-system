package com.iseonjwa.payment.pg.infrastructure;

import com.iseonjwa.payment.payment.domain.PgProvider;
import com.iseonjwa.payment.pg.application.PgApprovalGate;
import com.iseonjwa.payment.pg.application.PgPermit;
import com.iseonjwa.payment.pg.application.PgUnavailableException;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;

public class Resilience4jPgApprovalGate implements PgApprovalGate {
    private final Resilience4jPgGroup mock;
    private final Resilience4jPgGroup toss;

    Resilience4jPgApprovalGate(Resilience4jPgGroup mock, Resilience4jPgGroup toss) {
        this.mock = mock;
        this.toss = toss;
    }

    /** 그룹 하나만 쓰는 테스트용이다. 토스 그룹도 같은 그룹을 가리킨다. */
    Resilience4jPgApprovalGate(Resilience4jPgGroup group) {
        this(group, group);
    }

    @Override
    public PgPermit acquire(PgProvider provider) {
        var group = group(provider);
        return group.tryAcquire().orElseThrow(() -> new PgUnavailableException(
                provider + " 승인 허가를 얻지 못했다 state=" + group.circuitBreaker().getState()));
    }

    @Override
    public boolean accepting(PgProvider provider) {
        var state = group(provider).circuitBreaker().getState();
        return state != CircuitBreaker.State.OPEN && state != CircuitBreaker.State.FORCED_OPEN;
    }

    /** Mock(기본) 그룹 {@code pg-approve}의 CB. 그룹을 가리지 않던 기존 테스트가 쓴다. */
    public CircuitBreaker circuitBreaker() {
        return circuitBreaker(PgProvider.MOCK);
    }

    /** Mock(기본) 그룹 {@code pg-approve}의 Bulkhead. */
    public Bulkhead bulkhead() {
        return bulkhead(PgProvider.MOCK);
    }

    public CircuitBreaker circuitBreaker(PgProvider provider) {
        return group(provider).circuitBreaker();
    }

    public Bulkhead bulkhead(PgProvider provider) {
        return group(provider).bulkhead();
    }

    private Resilience4jPgGroup group(PgProvider provider) {
        return switch (provider) {
            case MOCK -> mock;
            case TOSS -> toss;
        };
    }
}
