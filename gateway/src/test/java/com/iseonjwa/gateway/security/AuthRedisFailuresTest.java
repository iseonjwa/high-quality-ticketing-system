package com.iseonjwa.gateway.security;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.iseonjwa.gateway.security.AuthRedisFailures.Cause;
import com.iseonjwa.gateway.security.AuthRedisFailures.RedisLookupTimeout;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.lettuce.core.RedisCommandTimeoutException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.client.circuitbreaker.NoFallbackAvailableException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.RedisSystemException;

import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class AuthRedisFailuresTest {
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final CircuitBreakerRegistry breakers = CircuitBreakerRegistry.ofDefaults();
    private final AtomicLong nanos = new AtomicLong();
    private final AuthRedisFailures failures = new AuthRedisFailures(meters, breakers, nanos::get);
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private final Logger logger = (Logger) LoggerFactory.getLogger(AuthRedisFailures.class);

    @BeforeEach
    void attachAppender() {
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void detachAppender() {
        logger.detachAppender(logs);
    }

    @Test
    void 실패_원인을_나눈다() {
        // bulkhead_full은 실제 BulkheadFullException이 필요해 AuthRedisResilienceContextTest에서 확인한다.
        assertThat(AuthRedisFailures.classify(new RedisLookupTimeout())).isEqualTo(Cause.LOOKUP_TIMEOUT);
        assertThat(AuthRedisFailures.classify(new TimeoutException())).isEqualTo(Cause.TIME_LIMITER);
        assertThat(AuthRedisFailures.classify(CallNotPermittedException.createCallNotPermittedException(
                breakers.circuitBreaker("other")))).isEqualTo(Cause.BREAKER_OPEN);
        assertThat(AuthRedisFailures.classify(new RedisConnectionFailureException("끊김"))).isEqualTo(Cause.REDIS_ERROR);
        assertThat(AuthRedisFailures.classify(new RedisCommandTimeoutException("명령 시한"))).isEqualTo(Cause.REDIS_ERROR);
        assertThat(AuthRedisFailures.classify(new IllegalStateException())).isEqualTo(Cause.OTHER);
    }

    @Test
    void 감싼_예외는_원인_사슬에서_찾는다() {
        var wrapped = new RedisSystemException("변환", new RedisCommandTimeoutException("명령 시한"));
        assertThat(AuthRedisFailures.classify(new IllegalStateException("감쌈", wrapped))).isEqualTo(Cause.REDIS_ERROR);
        assertThat(AuthRedisFailures.classify(new RuntimeException(new TimeoutException()))).isEqualTo(Cause.TIME_LIMITER);
        assertThat(AuthRedisFailures.classify(new NoFallbackAvailableException("감쌈", new RedisLookupTimeout())))
                .isEqualTo(Cause.LOOKUP_TIMEOUT);
        assertThat(AuthRedisFailures.classify(new RuntimeException(new IllegalStateException()))).isEqualTo(Cause.OTHER);
    }

    @Test
    void 로그의_예외_이름은_감싼_예외가_아니라_원인을_정한_예외다() {
        failures.record(new NoFallbackAvailableException("감쌈", new RedisLookupTimeout()), Duration.ofMillis(100));
        failures.record(new NoFallbackAvailableException("감쌈", new IllegalStateException()), Duration.ofMillis(1));

        assertThat(logs.list.get(0).getFormattedMessage()).contains("exception=" + RedisLookupTimeout.class.getName());
        assertThat(logs.list.get(1).getFormattedMessage()).contains("exception=" + IllegalStateException.class.getName());
    }

    @Test
    void 태그_값은_고정된_여섯_개다() {
        assertThat(Arrays.stream(Cause.values()).map(cause -> cause.tag)).containsExactly(
                "lookup_timeout", "time_limiter", "bulkhead_full", "breaker_open", "redis_error", "other");
        assertThat(meters.find(AuthRedisFailures.METER_NAME).counters()).hasSize(6);
    }

    @Test
    void 실패마다_원인_카운터를_올린다() {
        failures.record(new RedisLookupTimeout(), Duration.ofMillis(100));
        failures.record(new RedisLookupTimeout(), Duration.ofMillis(100));
        failures.record(new TimeoutException(), Duration.ofMillis(1000));

        assertThat(count("lookup_timeout")).isEqualTo(2.0);
        assertThat(count("time_limiter")).isEqualTo(1.0);
        assertThat(count("other")).isZero();
    }

    @Test
    void 같은_원인의_로그는_초당_한_건만_남기고_그_사이_건수를_합친다() {
        for (int i = 0; i < 5; i++) {
            failures.record(new RedisLookupTimeout(), Duration.ofMillis(100));
        }
        failures.record(new TimeoutException(), Duration.ofMillis(1000));
        assertThat(logs.list).hasSize(2);

        nanos.addAndGet(Duration.ofMillis(999).toNanos());
        failures.record(new RedisLookupTimeout(), Duration.ofMillis(100));
        assertThat(logs.list).hasSize(2);

        nanos.addAndGet(Duration.ofMillis(1).toNanos());
        failures.record(new RedisLookupTimeout(), Duration.ofMillis(100));
        assertThat(logs.list).hasSize(3);
        assertThat(logs.list.get(2).getFormattedMessage()).contains("suppressed=5");
        assertThat(count("lookup_timeout")).isEqualTo(7.0);
    }

    @Test
    void 로그에는_원인과_걸린_시간_스레드_차단기_상태_예외_이름을_남긴다() {
        breakers.circuitBreaker(GatewayAuthenticationFilter.AUTH_REDIS_BREAKER).transitionToOpenState();

        failures.record(new RedisConnectionFailureException("끊김"), Duration.ofMillis(42));

        var message = logs.list.getFirst().getFormattedMessage();
        assertThat(message).contains("cause=redis_error", "elapsedMs=42",
                "thread=" + Thread.currentThread().getName(), "breakerState=OPEN",
                "exception=" + RedisConnectionFailureException.class.getName());
        assertThat(message).doesNotContain("끊김");
    }

    private double count(String cause) {
        return meters.get(AuthRedisFailures.METER_NAME).tag("cause", cause).counter().count();
    }
}
