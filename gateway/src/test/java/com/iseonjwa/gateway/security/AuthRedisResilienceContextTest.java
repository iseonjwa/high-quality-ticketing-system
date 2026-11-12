package com.iseonjwa.gateway.security;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.iseonjwa.gateway.security.AuthRedisFailures.Cause;
import com.iseonjwa.gateway.security.AuthRedisFailures.RedisLookupTimeout;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.github.resilience4j.springboot3.bulkhead.autoconfigure.BulkheadAutoConfiguration;
import io.github.resilience4j.springboot3.circuitbreaker.autoconfigure.CircuitBreakerAutoConfiguration;
import io.github.resilience4j.springboot3.timelimiter.autoconfigure.TimeLimiterAutoConfiguration;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.support.SpringApplicationJsonEnvironmentPostProcessor;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cloud.circuitbreaker.resilience4j.ReactiveResilience4JAutoConfiguration;
import org.springframework.cloud.circuitbreaker.resilience4j.ReactiveResilience4jBulkheadProvider;
import org.springframework.cloud.client.circuitbreaker.ReactiveCircuitBreaker;
import org.springframework.cloud.client.circuitbreaker.ReactiveCircuitBreakerFactory;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.FileSystemResource;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;

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
    void 무시_예외가_비어_있으면_bulkhead_거절은_차단기_실패로_세어진다() {
        // 측정 덮어쓰기(compose.gateway-bulkhead.yml)가 AUTH_IGNORE_EXCEPTIONS를 안 줄 때 넣는 값이다.
        withApplicationJson(ignoreExceptionsJson("[]")).run(context -> {
            var calls = rejectOneByBulkhead(context);

            assertThat(calls.failed()).isEqualTo(1);
            assertThat(calls.ignored()).isZero();
        });
    }

    @Test
    void BulkheadFullException을_무시하면_bulkhead_거절은_실패가_아니라_ignored로_세어진다() {
        withApplicationJson(ignoreExceptionsJson("[\"io.github.resilience4j.bulkhead.BulkheadFullException\"]"))
                .run(context -> {
                    var calls = rejectOneByBulkhead(context);

                    assertThat(calls.failed()).isZero();
                    assertThat(calls.ignored()).isEqualTo(1);
                });
    }

    @Test
    void 운영_설정_그대로면_bulkhead_거절은_ignored로_세어지고_시간_창이_들어간다() {
        // config-repo/gateway.yml을 그대로 얹는다. bulkhead 동시 한도만 이 클래스의 1이 이겨서 거절 한 번을 만든다.
        withConfigRepo().run(context -> {
            var config = context.getBean(CircuitBreakerRegistry.class).circuitBreaker(BREAKER).getCircuitBreakerConfig();
            assertThat(config.getSlidingWindowType()).isEqualTo(CircuitBreakerConfig.SlidingWindowType.TIME_BASED);
            assertThat(config.getMinimumNumberOfCalls()).isEqualTo(500);

            var calls = rejectOneByBulkhead(context);

            assertThat(calls.failed()).isZero();
            assertThat(calls.ignored()).isEqualTo(1);
        });
    }

    @Test
    void 운영_설정_그대로면_차단기_열림_거절의_Retry_After는_열림_시간_5초다() {
        // 자동 설정이 만든 등록소의 auth-redis 설정에서 읽는지 본다. 열림 시간을 바꾸면 Retry-After도 따라온다.
        // Note: 실제 기동처럼 "3s" 기본값을 Duration으로 바꾸려면 ApplicationConversionService가 필요하다(GatewayAuthenticationFilterTimeoutTest 참고).
        withConfigRepo().withInitializer(context -> context.getBeanFactory()
                        .setConversionService(ApplicationConversionService.getSharedInstance()))
                .withUserConfiguration(AuthRedisRetryAfter.class).run(context -> {
            breaker(context);
            var retryAfter = context.getBean(AuthRedisRetryAfter.class);

            assertThat(retryAfter.of(Cause.BREAKER_OPEN)).isEqualTo(Duration.ofSeconds(5));
            assertThat(retryAfter.of(Cause.LOOKUP_TIMEOUT)).isEqualTo(Duration.ofSeconds(3));
        });
    }

    @Test
    void 운영_시한은_Lettuce가_필터보다_길고_필터가_느린_호출_기준보다_길다() throws IOException {
        // Note: 순서가 뒤집히면 시한 초과가 lookup_timeout이 아닌 다른 원인으로 남거나, 느린 호출과 실패로 함께 세어진다(ADR 0050).
        var environment = new StandardEnvironment();
        configRepoSources().forEach(source -> environment.getPropertySources().addLast(source));
        // "200ms" 같은 값은 앱과 같은 Binder 변환으로 읽는다
        var binder = new Binder(ConfigurationPropertySources.get(environment));
        var lettuce = binder.bind("spring.data.redis.timeout", Duration.class).get();
        var filter = binder.bind("auth.redis-timeout", Duration.class).get();
        var slowCall = binder.bind(
                "resilience4j.circuitbreaker.instances.auth-redis.slow-call-duration-threshold", Duration.class).get();

        assertThat(lettuce).isGreaterThan(filter);
        assertThat(filter).isGreaterThan(slowCall);
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

    // Note: SPRING_APPLICATION_JSON은 기동 단계의 후처리기가 속성으로 펼친다. 같은 후처리기를 거쳐 빈 배열 같은 JSON 모양을 그대로 확인한다.
    private ApplicationContextRunner withApplicationJson(String json) {
        return runner.withInitializer(context -> {
            var environment = context.getEnvironment();
            environment.getPropertySources().addFirst(new MapPropertySource("test-json", Map.of("spring.application.json", json)));
            new SpringApplicationJsonEnvironmentPostProcessor().postProcessEnvironment(environment, new SpringApplication());
        });
    }

    private ApplicationContextRunner withConfigRepo() {
        return runner.withInitializer(context -> {
            try {
                configRepoSources().forEach(source -> context.getEnvironment().getPropertySources().addLast(source));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    private static List<PropertySource<?>> configRepoSources() throws IOException {
        return new YamlPropertySourceLoader().load("config-repo-gateway", new FileSystemResource("../config-repo/gateway.yml"));
    }

    private static String ignoreExceptionsJson(String exceptions) {
        return "{\"resilience4j\":{\"circuitbreaker\":{\"instances\":{\"auth-redis\":{\"ignoreExceptions\":"
                + exceptions + "}}}}}";
    }

    // Note: Grafana의 resilience4j_circuitbreaker_calls_seconds_count{kind=...}와 같은 지표를 자동 설정과 같은 방식으로 붙여 센다.
    private BreakerCalls rejectOneByBulkhead(AssertableApplicationContext context) {
        var meters = new SimpleMeterRegistry();
        TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(context.getBean(CircuitBreakerRegistry.class)).bindTo(meters);
        var holding = holdOneCall(context);
        try {
            breaker(context).run(Mono.just(true)).onErrorResume(e -> Mono.empty()).block();
        } finally {
            holding.tryEmitValue(true);
        }
        return new BreakerCalls(callCount(meters, "failed"), callCount(meters, "ignored"));
    }

    private static long callCount(MeterRegistry meters, String kind) {
        return meters.get("resilience4j.circuitbreaker.calls").tag("name", BREAKER).tag("kind", kind).timer().count();
    }

    private record BreakerCalls(long failed, long ignored) {}

    private Sinks.One<Boolean> holdOneCall(AssertableApplicationContext context) {
        var holding = Sinks.<Boolean>one();
        breaker(context).run(holding.asMono()).subscribe();
        return holding;
    }

    private ReactiveCircuitBreaker breaker(AssertableApplicationContext context) {
        return context.getBean(ReactiveCircuitBreakerFactory.class).create(BREAKER);
    }
}
