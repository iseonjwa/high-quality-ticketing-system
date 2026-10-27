package com.iseonjwa.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Gateway의 Redis 호출 보호 기준 (SA 9-4, 9-5). 기본값은 SA의 초기값이고 측정 후 config-repo에서 바꾼다.
 *
 * @param timeout               호출 하나의 타임아웃. 정상 응답은 1ms 안팎이라 100ms는 이상 징후다
 * @param slidingWindowSize     최근 몇 번의 호출로 판단할지
 * @param failureRateThreshold  실패율(%)이 이 값 이상이면 OPEN
 * @param slowCallDuration      이 시간을 넘으면 느린 호출로 본다
 * @param slowCallRateThreshold 느린 호출 비율(%)이 이 값 이상이면 OPEN
 * @param waitInOpenState       OPEN 뒤 HALF_OPEN으로 넘어가기까지 기다리는 시간
 * @param halfOpenCalls         HALF_OPEN에서 시험 삼아 보내는 호출 수
 */
@ConfigurationProperties(prefix = "gateway.redis-guard")
public record RedisGuardProperties(
        @DefaultValue("100ms") Duration timeout,
        @DefaultValue("20") int slidingWindowSize,
        @DefaultValue("50") float failureRateThreshold,
        @DefaultValue("50ms") Duration slowCallDuration,
        @DefaultValue("50") float slowCallRateThreshold,
        @DefaultValue("10s") Duration waitInOpenState,
        @DefaultValue("5") int halfOpenCalls) {
}
