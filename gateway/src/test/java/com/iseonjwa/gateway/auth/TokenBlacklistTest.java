package com.iseonjwa.gateway.auth;

import com.iseonjwa.gateway.config.RedisCircuitBreakerConfig;
import com.iseonjwa.gateway.config.RedisGuardProperties;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.circuitbreaker.resilience4j.ReactiveResilience4JCircuitBreakerFactory;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JConfigurationProperties;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Redis 장애·느려짐 정책 (SA 9-5): 블랙리스트 확인은 fail-open, 100ms 타임아웃, 서킷 브레이커. */
class TokenBlacklistTest {

    private final ReactiveStringRedisTemplate redisTemplate = mock(ReactiveStringRedisTemplate.class);
    private final CircuitBreakerRegistry circuitBreakerRegistry = CircuitBreakerRegistry.ofDefaults();
    private TokenBlacklist tokenBlacklist;

    @BeforeEach
    void setUp() {
        ReactiveResilience4JCircuitBreakerFactory factory = new ReactiveResilience4JCircuitBreakerFactory(
                circuitBreakerRegistry, TimeLimiterRegistry.ofDefaults(), null,
                new Resilience4JConfigurationProperties());
        RedisGuardProperties defaults = new RedisGuardProperties(
                Duration.ofMillis(100), 20, 50, Duration.ofMillis(50), 50, Duration.ofSeconds(10), 5);
        new RedisCircuitBreakerConfig().redisCircuitBreakerCustomizer(defaults).customize(factory);
        tokenBlacklist = new TokenBlacklist(redisTemplate, factory);
    }

    @Test
    void 블랙리스트에_있으면_true다() {
        when(redisTemplate.hasKey("auth:blacklist:jti-1")).thenReturn(Mono.just(true));

        StepVerifier.create(tokenBlacklist.isRevoked("jti-1")).expectNext(true).verifyComplete();
    }

    @Test
    void Redis_오류면_통과시킨다() {
        when(redisTemplate.hasKey(anyString())).thenReturn(Mono.error(new IllegalStateException("redis down")));

        StepVerifier.create(tokenBlacklist.isRevoked("jti-1")).expectNext(false).verifyComplete();
    }

    @Test
    void Redis가_100ms_안에_답하지_않으면_기다리지_않고_통과시킨다() {
        when(redisTemplate.hasKey(anyString())).thenReturn(Mono.just(true).delayElement(Duration.ofSeconds(2)));

        StepVerifier.create(tokenBlacklist.isRevoked("jti-1"))
                .expectNext(false)
                .expectComplete()
                .verify(Duration.ofMillis(800));
    }

    @Test
    void 최근_20회_중_절반이_실패하면_서킷이_열린다() {
        when(redisTemplate.hasKey(anyString())).thenReturn(Mono.error(new IllegalStateException("redis down")));

        for (int i = 0; i < 20; i++) {
            tokenBlacklist.isRevoked("jti-" + i).block();
        }

        CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker(RedisCircuitBreakerConfig.REDIS);
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
    }
}
