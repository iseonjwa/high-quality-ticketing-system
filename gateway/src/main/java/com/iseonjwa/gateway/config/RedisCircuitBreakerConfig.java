package com.iseonjwa.gateway.config;

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import org.springframework.cloud.circuitbreaker.resilience4j.ReactiveResilience4JCircuitBreakerFactory;
import org.springframework.cloud.client.circuitbreaker.Customizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Redis 호출용 서킷 브레이커 "redis" (SA 9-4: 최근 20회 중 50% 실패 또는 50ms 초과 시 OPEN,
 * 10초 뒤 HALF_OPEN 5회 시험). 타임아웃은 TimeLimiter로 건다.
 */
@Configuration(proxyBeanMethods = false)
public class RedisCircuitBreakerConfig {

    public static final String REDIS = "redis";

    @Bean
    public Customizer<ReactiveResilience4JCircuitBreakerFactory> redisCircuitBreakerCustomizer(
            RedisGuardProperties properties) {
        CircuitBreakerConfig circuitBreakerConfig = CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(properties.slidingWindowSize())
                // 기본값(100)이 창 크기보다 크면 서킷이 영원히 열리지 않는다
                .minimumNumberOfCalls(properties.slidingWindowSize())
                .failureRateThreshold(properties.failureRateThreshold())
                .slowCallDurationThreshold(properties.slowCallDuration())
                .slowCallRateThreshold(properties.slowCallRateThreshold())
                .waitDurationInOpenState(properties.waitInOpenState())
                .permittedNumberOfCallsInHalfOpenState(properties.halfOpenCalls())
                .build();
        TimeLimiterConfig timeLimiterConfig = TimeLimiterConfig.custom()
                .timeoutDuration(properties.timeout())
                .build();

        return factory -> factory.configure(
                builder -> builder.circuitBreakerConfig(circuitBreakerConfig).timeLimiterConfig(timeLimiterConfig),
                REDIS);
    }
}
