package com.iseonjwa.reservation.queue.infrastructure;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;

@Configuration
@EnableConfigurationProperties(QueueRedisCircuitBreakerProperties.class)
public class QueueRedisCircuitBreakerConfiguration {

    static final String NAME = "queue-redis"; // 차단기 이름

    // 차단기 보관함
    @Bean
    CircuitBreakerRegistry queueCircuitBreakerRegistry() {
        return CircuitBreakerRegistry.ofDefaults(); // 빈 차단기 보관함
    }

    // 실제 차단기
    @Bean
    CircuitBreaker queueRedisCircuitBreaker(CircuitBreakerRegistry circuitBreakerRegistry,
                                            QueueRedisCircuitBreakerProperties queueRedisCircuitBreakerProperties) {
        return circuitBreakerRegistry.circuitBreaker(NAME, circuitBreakerConfig(queueRedisCircuitBreakerProperties));
    }

    // 보관함 안의 차단기 상태(열림·닫힘, 실패율, 느린 호출 비율 등)를 지표 연결
    // 지표: resilience4j_circuitbreaker_state{name="queue-redis"} 등
    @Bean
    MeterBinder queueCircuitBreakerMetrics(CircuitBreakerRegistry circuitBreakerRegistry) {
        return TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(circuitBreakerRegistry);
    }

    static CircuitBreakerConfig circuitBreakerConfig(QueueRedisCircuitBreakerProperties p) {
        return CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.TIME_BASED)
                .slidingWindowSize(p.slidingWindowSize())
                .minimumNumberOfCalls(p.minimumNumberOfCalls())
                .failureRateThreshold(p.failureRateThreshold())
                .slowCallDurationThreshold(p.slowCallDurationThreshold())
                .slowCallRateThreshold(p.slowCallRateThreshold())
                .waitDurationInOpenState(p.waitDurationInOpenState())
                .permittedNumberOfCallsInHalfOpenState(p.permittedNumberOfCallsInHalfOpenState())
                // Redis 장애만 실패로 센다. 목록에 없는 예외(버그 등)는 성공으로 세진다
                .recordExceptions(QueryTimeoutException.class, RedisConnectionFailureException.class)
                .build();
    }
}
