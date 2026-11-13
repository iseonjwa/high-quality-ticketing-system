package com.iseonjwa.gateway.security;

import com.iseonjwa.gateway.security.AuthRedisFailures.Cause;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 로그아웃 확인(auth-redis) 실패 503에 넣을 {@code Retry-After}를 원인에 따라 고른다.
 * 차단기가 열려 거절했으면 열림 시간, 닫힌 상태에서 이번 확인만 실패했으면 {@code auth.redis-retry-after}(기본 3초)다.
 */
@Component
public class AuthRedisRetryAfter {
    private final CircuitBreakerRegistry breakers;
    private final Duration singleFailure;

    // Note: 3초는 대기열 최소 조회 간격(queue.poll-min)과 같다. 열림 거절도 대기열(ISE-59)처럼 남은 시간이 아닌 열림 시간 전체를 준다.
    // 1초로 주면 열린 5초 동안 사용자 한 명이 거절될 요청을 4~5번 보내고, 반열림 순간에 몰려 다시 열리게 만들 수 있다.
    public AuthRedisRetryAfter(CircuitBreakerRegistry breakers,
            @Value("${auth.redis-retry-after:3s}") Duration singleFailure) {
        if (singleFailure.toSeconds() < 1) {
            throw new IllegalArgumentException("auth.redis-retry-after는 1초 이상이어야 한다: " + singleFailure);
        }
        this.breakers = breakers;
        this.singleFailure = singleFailure;
    }

    Duration of(Cause cause) {
        if (cause != Cause.BREAKER_OPEN) {
            return singleFailure;
        }
        return breakers.find(GatewayAuthenticationFilter.AUTH_REDIS_BREAKER)
                .map(breaker -> breaker.getCircuitBreakerConfig().getWaitIntervalFunctionInOpenState().apply(1))
                .map(AuthRedisRetryAfter::roundUpToSeconds)
                .orElse(singleFailure);
    }

    // Retry-After는 정수 초라 내림하면 열림이 끝나기 전에 다시 온다. 올림하고 최소 1초로 둔다.
    private static Duration roundUpToSeconds(long millis) {
        return Duration.ofSeconds(Math.max(1, (millis + 999) / 1000));
    }
}
