package com.iseonjwa.gateway.security;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.iseonjwa.gateway.security.AuthRedisFailures.Cause;
import com.iseonjwa.gateway.security.AuthRedisFailures.RedisLookupTimeout;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.springboot3.bulkhead.autoconfigure.BulkheadAutoConfiguration;
import io.github.resilience4j.springboot3.circuitbreaker.autoconfigure.CircuitBreakerAutoConfiguration;
import io.github.resilience4j.springboot3.timelimiter.autoconfigure.TimeLimiterAutoConfiguration;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cloud.circuitbreaker.resilience4j.ReactiveResilience4JAutoConfiguration;
import org.springframework.cloud.circuitbreaker.resilience4j.ReactiveResilience4jBulkheadProvider;
import org.springframework.cloud.client.circuitbreaker.ReactiveCircuitBreaker;
import org.springframework.cloud.client.circuitbreaker.ReactiveCircuitBreakerFactory;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 실제 자동 설정으로 auth-redis 차단기의 감싸는 순서와 bulkhead 켜고 끄기를 확인한다(ISE-230 3번). */
class AuthRedisResilienceContextTest {
    private static final String BREAKER = GatewayAuthenticationFilter.AUTH_REDIS_BREAKER;

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(CircuitBreakerAutoConfiguration.class,
                    TimeLimiterAutoConfiguration.class, BulkheadAutoConfiguration.class,
                    ReactiveResilience4JAutoConfiguration.class))
            .withPropertyValues(
                    "resilience4j.bulkhead.instances.auth-redis.max-concurrent-calls=1",
                    "resilience4j.bulkhead.instances.auth-redis.max-wait-duration=0");

    @Test
    void bulkhead_기본값이면_provider_빈이_있고_동시_호출_한도를_넘으면_bulkhead_full로_분류된다() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(ReactiveResilience4jBulkheadProvider.class);
            var holding = holdOneCall(context);
            try {
                assertThatThrownBy(() -> breaker(context).run(Mono.just(true)).block())
                        .satisfies(failure -> assertThat(failure.getCause().getClass().getName())
                                .isEqualTo("io.github.resilience4j.bulkhead.BulkheadFullException"))
                        .satisfies(failure -> assertThat(AuthRedisFailures.classify(failure))
                                .isEqualTo(Cause.BULKHEAD_FULL));
            } finally {
                holding.tryEmitValue(true);
            }
        });
    }

    @Test
    void bulkhead를_끄면_provider_빈이_없고_같은_동시_호출이_통과한다() {
        runner.withPropertyValues("spring.cloud.circuitbreaker.bulkhead.resilience4j.enabled=false").run(context -> {
            assertThat(context).doesNotHaveBean(ReactiveResilience4jBulkheadProvider.class);
            var holding = holdOneCall(context);
            try {
                assertThat(breaker(context).run(Mono.just(true)).block()).isTrue();
            } finally {
                holding.tryEmitValue(true);
            }
        });
    }

    @Test
    void 안쪽_시한은_차단기에_한_번만_기록되고_바깥_TimeoutException은_두_번_기록된다() {
        runner.run(context -> {
            var registry = context.getBean(CircuitBreakerRegistry.class);

            breaker(context).run(Mono.<Boolean>never().timeout(Duration.ofMillis(50),
                    Mono.error(RedisLookupTimeout::new))).onErrorResume(e -> Mono.empty()).block();
            assertThat(registry.circuitBreaker(BREAKER).getMetrics().getNumberOfBufferedCalls()).isEqualTo(1);

            // Note: 라이브러리는 TimeLimiter 시한 초과를 doOnError(TimeoutException)로 직접 기록한다.
            // 안쪽에서 TimeoutException이 나오면 차단기 연산자와 이 기록이 겹쳐 한 실패가 두 번 들어간다.
            breaker(context).run(Mono.<Boolean>never().timeout(Duration.ofMillis(50)))
                    .onErrorResume(e -> Mono.empty()).block();
            assertThat(registry.circuitBreaker(BREAKER).getMetrics().getNumberOfBufferedCalls()).isEqualTo(3);
        });
    }

    @Test
    void 자동_설정이_차단기_이벤트_로그_customizer를_적용한다() {
        var logs = new ListAppender<ILoggingEvent>();
        var logger = (Logger) LoggerFactory.getLogger(AuthRedisBreakerEvents.class);
        logs.start();
        logger.addAppender(logs);
        try {
            runner.withBean(AuthRedisBreakerEvents.class).run(context -> {
                breaker(context).run(Mono.just(true)).block();

                context.getBean(CircuitBreakerRegistry.class).circuitBreaker(BREAKER).transitionToOpenState();

                assertThat(logs.list).singleElement().extracting(ILoggingEvent::getFormattedMessage).asString()
                        .contains("from=CLOSED to=OPEN");
            });
        } finally {
            logger.detachAppender(logs);
        }
    }

    @Test
    void 컴포넌트_스캔과_같은_방식으로_등록하면_auth_redis_기록_빈이_만들어진다() {
        // Note: withBean(Class)은 public 생성자를 바로 골라 생성자 선택 오류를 숨긴다.
        // 컴포넌트 스캔과 같은 경로(@Autowired 생성자 탐색)를 타도록 withUserConfiguration으로 등록한다.
        runner.withBean(MeterRegistry.class, SimpleMeterRegistry::new)
                .withUserConfiguration(AuthRedisFailures.class, AuthRedisBreakerEvents.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(AuthRedisFailures.class);
                    assertThat(context).hasSingleBean(AuthRedisBreakerEvents.class);
                });
    }

    private Sinks.One<Boolean> holdOneCall(AssertableApplicationContext context) {
        var holding = Sinks.<Boolean>one();
        breaker(context).run(holding.asMono()).subscribe();
        return holding;
    }

    private ReactiveCircuitBreaker breaker(AssertableApplicationContext context) {
        return context.getBean(ReactiveCircuitBreakerFactory.class).create(BREAKER);
    }
}
