package com.iseonjwa.payment.warmup;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 기동 웜업 설정(events-and-reliability.md 기동 웜업 절).
 *
 * @param enabled     끄면 웜업 빈을 만들지 않는다. 테스트 프로필과 ops 프로필은 끈다
 * @param iterations  단계마다 되풀이하는 회차 수. 1 이상 1000 이하
 * @param maxDuration 웜업 전체의 시간 상한. 넘으면 남은 회차를 그만둔다
 */
@ConfigurationProperties(prefix = "payment.warmup")
public record WarmupProperties(Boolean enabled, Integer iterations, Duration maxDuration) {
    private static final int DEFAULT_ITERATIONS = 20;
    private static final Duration DEFAULT_MAX_DURATION = Duration.ofSeconds(5);

    public WarmupProperties {
        enabled = enabled == null || enabled;
        iterations = iterations != null ? iterations : DEFAULT_ITERATIONS;
        maxDuration = maxDuration != null ? maxDuration : DEFAULT_MAX_DURATION;
        if (iterations < 1 || iterations > 1000) {
            throw new IllegalArgumentException("payment.warmup.iterations는 1 이상 1000 이하여야 합니다");
        }
        if (maxDuration.isZero() || maxDuration.isNegative()) {
            throw new IllegalArgumentException("payment.warmup.max-duration은 0보다 커야 합니다");
        }
    }
}
