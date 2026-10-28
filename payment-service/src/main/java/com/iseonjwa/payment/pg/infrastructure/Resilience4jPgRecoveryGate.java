package com.iseonjwa.payment.pg.infrastructure;

import com.iseonjwa.payment.pg.application.PgRecoveryGate;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;

import java.util.Optional;
import java.util.Objects;
import java.util.function.Supplier;

public class Resilience4jPgRecoveryGate implements PgRecoveryGate {
    private final Resilience4jPgGroup group;

    Resilience4jPgRecoveryGate(Resilience4jPgGroup group) {
        this.group = group;
    }

    @Override
    public <R> Optional<R> execute(Supplier<R> call) {
        var permit = group.tryAcquire();
        if (permit.isEmpty()) return Optional.empty();
        // close()가 아직 정산하지 않았으면 반환한다. 어떤 경로로 끝나도 허가가 새지 않는다
        try (var held = permit.get()) {
            held.callStarted();
            R result;
            try {
                result = Objects.requireNonNull(call.get(), "PG 호출 결과가 null이다");
            } catch (RuntimeException exception) {
                held.failure();
                throw exception;
            }
            held.settle(result);
            return Optional.of(result);
        }
    }

    public CircuitBreaker circuitBreaker() {
        return group.circuitBreaker();
    }

    public Bulkhead bulkhead() {
        return group.bulkhead();
    }
}
