package com.iseonjwa.payment.pg.infrastructure;

import com.iseonjwa.payment.pg.application.PgBillingGate;
import com.iseonjwa.payment.pg.application.PgPermit;
import com.iseonjwa.payment.pg.application.PgUnavailableException;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;

public class Resilience4jPgBillingGate implements PgBillingGate {
    private final Resilience4jPgGroup group;

    Resilience4jPgBillingGate(Resilience4jPgGroup group) {
        this.group = group;
    }

    @Override
    public PgPermit acquire() {
        return group.tryAcquire().orElseThrow(() -> new PgUnavailableException(
                "pg-billing 허가를 얻지 못했다 state=" + group.circuitBreaker().getState()));
    }

    public CircuitBreaker circuitBreaker() {
        return group.circuitBreaker();
    }

    public Bulkhead bulkhead() {
        return group.bulkhead();
    }
}
