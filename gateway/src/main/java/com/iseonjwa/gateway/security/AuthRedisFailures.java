package com.iseonjwa.gateway.security;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.lettuce.core.RedisException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * 로그아웃 확인(auth-redis) 실패를 원인별로 세고, 원인마다 초당 한 건까지만 WARN으로 남긴다.
 * 응답은 바꾸지 않는다. 호출하는 쪽이 503을 그대로 쓴다.
 */
@Component
public class AuthRedisFailures {
    static final String METER_NAME = "gateway.auth.redis.failures";
    private static final Logger log = LoggerFactory.getLogger(AuthRedisFailures.class);
    private static final long LOG_INTERVAL_NANOS = Duration.ofSeconds(1).toNanos();
    private static final int MAX_CAUSE_DEPTH = 10;
    // Note: resilience4j-bulkhead는 Gateway의 실행 경로에만 있고 컴파일 경로에는 없다. 의존성을 늘리지 않으려고 이름으로 비교한다.
    private static final String BULKHEAD_FULL_EXCEPTION = "io.github.resilience4j.bulkhead.BulkheadFullException";

    /** 태그 값은 이 여섯 개로 고정한다. 예외 이름을 태그에 그대로 넣으면 시계열 수가 늘어난다. */
    enum Cause {
        LOOKUP_TIMEOUT("lookup_timeout"),
        TIME_LIMITER("time_limiter"),
        BULKHEAD_FULL("bulkhead_full"),
        BREAKER_OPEN("breaker_open"),
        REDIS_ERROR("redis_error"),
        OTHER("other");

        final String tag;

        Cause(String tag) {
            this.tag = tag;
        }
    }

    /** 필터 안쪽 Redis 조회 시한(100ms) 초과. 바깥 TimeLimiter의 TimeoutException과 구별하려고 따로 둔다. */
    static final class RedisLookupTimeout extends RuntimeException {
        RedisLookupTimeout() {
            super("Redis 로그아웃 조회가 시한을 넘었습니다", null, false, false);
        }
    }

    private final CircuitBreakerRegistry breakers;
    private final LongSupplier nanoClock;
    private final Map<Cause, Counter> counters = new EnumMap<>(Cause.class);
    private final Map<Cause, AtomicLong> nextLogAt = new EnumMap<>(Cause.class);
    private final Map<Cause, AtomicLong> suppressed = new EnumMap<>(Cause.class);

    // Note: 생성자가 둘이면 스프링은 @Autowired가 붙은 쪽을 쓴다. 빼면 기본 생성자를 찾다 기동에 실패한다(TS-53).
    @Autowired
    public AuthRedisFailures(MeterRegistry meters, CircuitBreakerRegistry breakers) {
        this(meters, breakers, System::nanoTime);
    }

    AuthRedisFailures(MeterRegistry meters, CircuitBreakerRegistry breakers, LongSupplier nanoClock) {
        this.breakers = breakers;
        this.nanoClock = nanoClock;
        var now = nanoClock.getAsLong();
        for (var cause : Cause.values()) {
            counters.put(cause, Counter.builder(METER_NAME)
                    .description("Gateway 로그아웃 확인(auth-redis) 실패 수")
                    .tag("cause", cause.tag)
                    .register(meters));
            nextLogAt.put(cause, new AtomicLong(now));
            suppressed.put(cause, new AtomicLong());
        }
    }

    /** 실패를 받은 스레드에서 부른다. 스레드 이름이 로그에 들어간다. */
    void record(Throwable failure, Duration elapsed) {
        var decisive = decisiveFailure(failure);
        var cause = classifyOne(decisive);
        counters.get(cause).increment();
        if (!tryAcquireLogSlot(cause)) {
            suppressed.get(cause).incrementAndGet();
            return;
        }
        // Note: 요청마다 로그를 쓰면 병목인 Gateway CPU를 더 쓴다. 원인마다 초당 한 건만 쓰고 그 사이 건수는 suppressed로 합친다.
        // 토큰·jti 같은 식별자는 넣지 않는다.
        log.warn("auth-redis 실패 cause={} elapsedMs={} thread={} breakerState={} exception={} suppressed={}",
                cause.tag, elapsed.toMillis(), Thread.currentThread().getName(), breakerState(),
                decisive.getClass().getName(), suppressed.get(cause).getAndSet(0));
    }

    static Cause classify(Throwable failure) {
        return classifyOne(decisiveFailure(failure));
    }

    // Note: 대체 응답 없이 run()을 쓰면 라이브러리가 모든 실패를 NoFallbackAvailableException으로 감싼다.
    // 그래서 원인 사슬을 바깥부터 보며 처음 분류되는 예외를 쓰고, 없으면 가장 안쪽 예외를 쓴다.
    // 필터 안쪽 시한은 RedisLookupTimeout으로 내므로 남은 TimeoutException은 차단기 바깥 TimeLimiter(기본 1초)가 낸 것이다.
    private static Throwable decisiveFailure(Throwable failure) {
        var current = failure;
        for (int depth = 0; depth < MAX_CAUSE_DEPTH; depth++) {
            var next = current.getCause();
            if (classifyOne(current) != Cause.OTHER || next == null || next == current) {
                return current;
            }
            current = next;
        }
        return current;
    }

    private static Cause classifyOne(Throwable failure) {
        if (failure instanceof RedisLookupTimeout) {
            return Cause.LOOKUP_TIMEOUT;
        }
        if (failure instanceof TimeoutException) {
            return Cause.TIME_LIMITER;
        }
        if (BULKHEAD_FULL_EXCEPTION.equals(failure.getClass().getName())) {
            return Cause.BULKHEAD_FULL;
        }
        if (failure instanceof CallNotPermittedException) {
            return Cause.BREAKER_OPEN;
        }
        if (failure instanceof DataAccessException || failure instanceof RedisException) {
            return Cause.REDIS_ERROR;
        }
        return Cause.OTHER;
    }

    private boolean tryAcquireLogSlot(Cause cause) {
        var slot = nextLogAt.get(cause);
        var now = nanoClock.getAsLong();
        var allowedAt = slot.get();
        return now - allowedAt >= 0 && slot.compareAndSet(allowedAt, now + LOG_INTERVAL_NANOS);
    }

    private String breakerState() {
        return breakers.find(GatewayAuthenticationFilter.AUTH_REDIS_BREAKER)
                .map(CircuitBreaker::getState)
                .map(Enum::name)
                .orElse("UNKNOWN");
    }
}
