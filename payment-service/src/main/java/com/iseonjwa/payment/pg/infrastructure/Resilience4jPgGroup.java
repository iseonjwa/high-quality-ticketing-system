package com.iseonjwa.payment.pg.infrastructure;

import com.iseonjwa.payment.pg.application.PgPermit;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 한 그룹(승인 또는 복구)의 Bulkhead와 Circuit Breaker. 허가의 획득과 정산을 이 클래스 한 곳에 모아
 * 반환을 빠뜨리지 않게 한다(0018).
 *
 * <p>Note: 허가를 얻은 뒤 CB의 OPEN 시간(기본 10초)보다 오래 정산하지 않으면 Resilience4j가 상태 전환 뒤의 호출로
 * 잘못 세는 경우가 있다(예: HALF_OPEN 중 반환이 시험 호출 자리를 늘림). 한 요청의 허가 보유 시간(TX1 1초 + PG 2.5초)이
 * OPEN 시간보다 충분히 짧아야 하므로 OPEN 시간을 크게 줄일 때 확인한다.
 */
final class Resilience4jPgGroup {
    private final CircuitBreaker circuitBreaker;
    private final Bulkhead bulkhead;

    /** 레지스트리 없이 만든다. 지표에 연결하지 않는 테스트용이다. */
    Resilience4jPgGroup(String name, PgProtectionProperties.Group settings) {
        this(name, settings, CircuitBreakerRegistry.ofDefaults(), BulkheadRegistry.ofDefaults());
    }

    /**
     * CB와 Bulkhead를 주어진 레지스트리에 등록해 만든다. Micrometer 지표({@code Tagged*Metrics})가 레지스트리를 보고
     * 그룹 이름({@code pg-approve}, {@code pg-recovery})으로 지표를 만든다(ADR 0024 결정 3).
     */
    Resilience4jPgGroup(String name, PgProtectionProperties.Group settings, CircuitBreakerRegistry circuitBreakers,
            BulkheadRegistry bulkheads) {
        this.circuitBreaker = circuitBreakers.circuitBreaker(name, CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(settings.slidingWindowSize())
                .minimumNumberOfCalls(settings.minimumNumberOfCalls())
                .failureRateThreshold(settings.failureRateThreshold())
                .waitDurationInOpenState(settings.openDuration())
                .permittedNumberOfCallsInHalfOpenState(settings.halfOpenPermittedCalls())
                .build());
        // 기본은 기다리지 않는다. 포화면 바로 거절해야 요청 스레드가 PG 장애에 묶이지 않는다.
        // 대기를 주면 그 시간만큼 자리를 기다려 순간 몰림을 흡수한다(PAY-2, ISE-238)
        this.bulkhead = bulkheads.bulkhead(name, BulkheadConfig.custom()
                .maxConcurrentCalls(settings.maxConcurrentCalls())
                .maxWaitDuration(settings.maxWaitDuration())
                .build());
    }

    CircuitBreaker circuitBreaker() {
        return circuitBreaker;
    }

    Bulkhead bulkhead() {
        return bulkhead;
    }

    /** 허가를 얻는다. 얻지 못하면 비어 있고 어떤 자원도 점유하지 않는다. */
    Optional<PgPermit> tryAcquire() {
        if (!circuitBreaker.tryAcquirePermission()) return Optional.empty();
        if (!bulkhead.tryAcquirePermission()) {
            // Note: CB 허가를 얻은 뒤 Bulkhead가 거절하면 CB 허가를 돌려준다. 안 그러면 HALF_OPEN의 시험 호출 자리가
            // 정산 없이 사라져 CB가 닫히지 않는다
            circuitBreaker.releasePermission();
            return Optional.empty();
        }
        return Optional.of(new Permit(circuitBreaker, bulkhead));
    }

    private static final class Permit implements PgPermit {
        private final CircuitBreaker circuitBreaker;
        private final Bulkhead bulkhead;
        // 허가를 얻은 시각으로 시작하고 PG 호출을 시작하면 그 시각으로 바꾼다(TX1 시간을 호출 시간에 섞지 않는다)
        private volatile long startedAtNanos = System.nanoTime();
        private final AtomicBoolean settled = new AtomicBoolean();

        private Permit(CircuitBreaker circuitBreaker, Bulkhead bulkhead) {
            this.circuitBreaker = circuitBreaker;
            this.bulkhead = bulkhead;
        }

        @Override
        public void callStarted() {
            startedAtNanos = System.nanoTime();
        }

        @Override
        public void success() {
            if (settled.compareAndSet(false, true)) {
                try {
                    circuitBreaker.onSuccess(elapsedNanos(), TimeUnit.NANOSECONDS);
                } finally {
                    bulkhead.onComplete();
                }
            }
        }

        @Override
        public void failure() {
            if (settled.compareAndSet(false, true)) {
                try {
                    circuitBreaker.onError(elapsedNanos(), TimeUnit.NANOSECONDS, new PgCallFailure());
                } finally {
                    bulkhead.onComplete();
                }
            }
        }

        @Override
        public void release() {
            if (settled.compareAndSet(false, true)) {
                try {
                    circuitBreaker.releasePermission();
                } finally {
                    bulkhead.onComplete();
                }
            }
        }

        @Override
        public void close() {
            release();
        }

        private long elapsedNanos() {
            return System.nanoTime() - startedAtNanos;
        }
    }

    /** CB에 실패로 기록하기 위한 표지. 호출 결과가 불확실했다는 뜻이다. */
    private static final class PgCallFailure extends RuntimeException {
        private PgCallFailure() {
            super("PG 호출 결과를 알 수 없다", null, false, false);
        }
    }
}
