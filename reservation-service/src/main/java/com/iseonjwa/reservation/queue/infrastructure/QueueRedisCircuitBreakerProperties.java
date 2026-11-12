package com.iseonjwa.reservation.queue.infrastructure;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "queue.redis.circuit-breaker")
public record QueueRedisCircuitBreakerProperties(int slidingWindowSize, // 최근 N초
                                                 int minimumNumberOfCalls, // 실패율 계산을 위한 최소 호출 수
                                                 float failureRateThreshold,
                                                 Duration slowCallDurationThreshold,
                                                 float slowCallRateThreshold,
                                                 Duration waitDurationInOpenState,
                                                 int permittedNumberOfCallsInHalfOpenState) {
}
