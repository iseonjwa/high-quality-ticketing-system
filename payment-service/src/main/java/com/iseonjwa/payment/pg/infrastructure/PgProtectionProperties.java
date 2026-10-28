package com.iseonjwa.payment.pg.infrastructure;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * PG 호출 보호 설정(pg-integration.md 5절). 값은 모두 {@code [가정]}이며 부하 측정 뒤 조정한다.
 */
@ConfigurationProperties(prefix = "payment.pg.protection")
public record PgProtectionProperties(Group approve, Group recovery) {
    public PgProtectionProperties {
        // 승인: 동시 10, 복구: 복구 실행기 스레드 수와 같은 2(0018)
        approve = approve != null ? approve : Group.defaults(10);
        recovery = recovery != null ? recovery : Group.defaults(2);
    }

    /**
     * @param maxConcurrentCalls  동시에 진행할 수 있는 호출 수. 넘으면 기다리지 않고 거절한다
     * @param slidingWindowSize   실패율을 계산하는 최근 호출 수
     * @param minimumNumberOfCalls 이 수만큼 모이기 전에는 CB가 열리지 않는다
     * @param failureRateThreshold 이 비율(%) 이상 실패하면 OPEN
     * @param openDuration        OPEN을 유지하는 시간
     * @param halfOpenPermittedCalls HALF_OPEN에서 시험 삼아 허용하는 호출 수
     */
    public record Group(Integer maxConcurrentCalls, Integer slidingWindowSize, Integer minimumNumberOfCalls,
            Float failureRateThreshold, Duration openDuration, Integer halfOpenPermittedCalls) {
        public Group {
            maxConcurrentCalls = positive(maxConcurrentCalls, 10, "max-concurrent-calls");
            slidingWindowSize = positive(slidingWindowSize, 20, "sliding-window-size");
            minimumNumberOfCalls = positive(minimumNumberOfCalls, slidingWindowSize, "minimum-number-of-calls");
            failureRateThreshold = failureRateThreshold != null ? failureRateThreshold : 50f;
            openDuration = openDuration != null ? openDuration : Duration.ofSeconds(10);
            halfOpenPermittedCalls = positive(halfOpenPermittedCalls, 3, "half-open-permitted-calls");
            if (failureRateThreshold <= 0 || failureRateThreshold > 100) {
                throw new IllegalArgumentException("failure-rate-threshold는 0 초과 100 이하여야 합니다");
            }
        }

        static Group defaults(int maxConcurrentCalls) {
            return new Group(maxConcurrentCalls, null, null, null, null, null);
        }

        private static int positive(Integer value, int defaultValue, String name) {
            var result = value != null ? value : defaultValue;
            if (result <= 0) throw new IllegalArgumentException("payment.pg.protection." + name + "은 1 이상이어야 합니다");
            return result;
        }
    }
}
