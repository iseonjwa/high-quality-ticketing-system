package com.iseonjwa.gateway.security;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cloud.client.circuitbreaker.ReactiveCircuitBreaker;
import org.springframework.cloud.client.circuitbreaker.ReactiveCircuitBreakerFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 인증 필터의 Redis 시한이 설정 속성 auth.redis-timeout에서 오고, 값이 없으면 운영 값과 같은 200ms인지 확인한다(ISE-233, ADR 0050). */
class GatewayAuthenticationFilterTimeoutTest {
    private static final Duration SLOWER_THAN_DEFAULT = Duration.ofMillis(250);

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final ReactiveStringRedisTemplate redis = mock(ReactiveStringRedisTemplate.class);

    // Note: SpringApplication은 빈 팩토리에 ApplicationConversionService를 넣어 "200ms" 같은 문자열을 Duration으로 바꾼다.
    // ApplicationContextRunner는 이 단계를 거치지 않으므로 같은 변환기를 직접 넣어 실제 기동과 같은 @Value 해석을 탄다.
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(context -> context.getBeanFactory()
                    .setConversionService(ApplicationConversionService.getSharedInstance()))
            .withBean(JwtDecoder.class, this::decoder)
            .withBean(ReactiveStringRedisTemplate.class, () -> redis)
            .withBean(GatewayErrorWriter.class, () -> new GatewayErrorWriter(JsonMapper.builder().build(), mock(Tracer.class)))
            .withBean(ReactiveCircuitBreakerFactory.class, GatewayAuthenticationFilterTimeoutTest::passThroughBreakers)
            .withBean(AuthRedisFailures.class, () -> new AuthRedisFailures(meters, CircuitBreakerRegistry.ofDefaults()))
            .withBean(CircuitBreakerRegistry.class, CircuitBreakerRegistry::ofDefaults)
            .withUserConfiguration(AuthRedisRetryAfter.class)
            .withUserConfiguration(GatewayAuthenticationFilter.class);

    @Test
    void 설정이_없으면_시한은_200ms다() {
        runner.run(context -> {
            when(redis.hasKey(anyString())).thenReturn(Mono.never());
            var startedAt = System.nanoTime();
            var exchange = filterOnce(context);
            var elapsed = Duration.ofNanos(System.nanoTime() - startedAt);

            assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(elapsed).isGreaterThanOrEqualTo(Duration.ofMillis(200));
            // auth.redis-retry-after가 없으면 한 번 실패의 Retry-After는 3초다
            assertThat(exchange.getResponse().getHeaders().getFirst("Retry-After")).isEqualTo("3");
        });
    }

    @Test
    void 설정이_없으면_200ms보다_늦은_조회는_lookup_timeout으로_503이다() {
        runner.run(context -> {
            when(redis.hasKey(anyString())).thenReturn(Mono.just(false).delayElement(SLOWER_THAN_DEFAULT));

            var exchange = filterOnce(context);

            assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(meters.get(AuthRedisFailures.METER_NAME).tag("cause", "lookup_timeout").counter().count())
                    .isEqualTo(1.0);
        });
    }

    @Test
    void 설정한_시한이_적용되어_기본_시한보다_늦은_조회도_통과한다() {
        runner.withPropertyValues("auth.redis-timeout=300ms").run(context -> {
            when(redis.hasKey(anyString())).thenReturn(Mono.just(false).delayElement(SLOWER_THAN_DEFAULT));
            var forwarded = new AtomicBoolean();

            var exchange = protectedRequest();
            context.getBean(GatewayAuthenticationFilter.class)
                    .filter(exchange, ignored -> Mono.fromRunnable(() -> forwarded.set(true))).block();

            assertThat(exchange.getResponse().getStatusCode()).isNotEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(forwarded).isTrue();
        });
    }

    private MockServerWebExchange filterOnce(AssertableApplicationContext context) {
        assertThat(context).hasNotFailed();
        var exchange = protectedRequest();
        context.getBean(GatewayAuthenticationFilter.class).filter(exchange, ignored -> Mono.empty()).block();
        return exchange;
    }

    private MockServerWebExchange protectedRequest() {
        return MockServerWebExchange.from(MockServerHttpRequest.get("http://localhost/api/v1/users/me")
                .header("Authorization", "Bearer access").build());
    }

    private JwtDecoder decoder() {
        var decoder = mock(JwtDecoder.class);
        var jwt = Jwt.withTokenValue("access")
                .header("alg", "HS256")
                .subject("1")
                .claim("jti", "test-jti")
                .claim("role", "USER")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .build();
        when(decoder.decode("access")).thenReturn(jwt);
        return decoder;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static ReactiveCircuitBreakerFactory passThroughBreakers() {
        ReactiveCircuitBreakerFactory factory = mock(ReactiveCircuitBreakerFactory.class);
        var breaker = mock(ReactiveCircuitBreaker.class);
        when(factory.create(anyString())).thenReturn(breaker);
        when(breaker.run(any(Mono.class))).thenAnswer(call -> call.getArgument(0));
        return factory;
    }
}
