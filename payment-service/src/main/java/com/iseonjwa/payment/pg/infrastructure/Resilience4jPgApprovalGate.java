package com.iseonjwa.payment.pg.infrastructure;

import com.iseonjwa.payment.pg.application.PgApprovalGate;
import com.iseonjwa.payment.pg.application.PgPermit;
import com.iseonjwa.payment.pg.application.PgUnavailableException;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;

public class Resilience4jPgApprovalGate implements PgApprovalGate {
    private final Resilience4jPgGroup group;

    Resilience4jPgApprovalGate(Resilience4jPgGroup group) {
        this.group = group;
    }

    @Override
    public PgPermit acquire() {
        return group.tryAcquire().orElseThrow(() -> new PgUnavailableException(
                "pg-approve 허가를 얻지 못했다 state=" + group.circuitBreaker().getState()));
    }

    public CircuitBreaker circuitBreaker() {
        return group.circuitBreaker();
    }

    public Bulkhead bulkhead() {
        return group.bulkhead();
    }
}
