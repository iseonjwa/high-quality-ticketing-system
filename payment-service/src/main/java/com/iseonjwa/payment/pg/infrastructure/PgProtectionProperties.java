package com.iseonjwa.payment.pg.infrastructure;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * PG 호출 보호 설정(pg-integration.md 5절). 값은 모두 {@code [가정]}이며 부하 측정 뒤 조정한다.
 */
@ConfigurationProperties(prefix = "payment.pg.protection")
public record PgProtectionProperties(Group approve, Group recovery) {
    // 승인: 동시 10, 복구: 복구 실행기 스레드 수와 같은 2(0018)
    private static final int APPROVE_MAX_CONCURRENT_CALLS = 10;
    private static final int RECOVERY_MAX_CONCURRENT_CALLS = 2;

    public PgProtectionProperties {
        // 그룹의 일부 값만 설정해도(환경변수 덮어쓰기 등) 동시 호출 수 기본값이 그룹별로 적용된다
        approve = (approve != null ? approve : Group.unset()).withMaxConcurrentCalls(APPROVE_MAX_CONCURRENT_CALLS);
        recovery = (recovery != null ? recovery : Group.unset()).withMaxConcurrentCalls(RECOVERY_MAX_CONCURRENT_CALLS);
    }

    /**
     * @param maxConcurrentCalls  동시에 진행할 수 있는 호출 수. 넘으면 기다리지 않고 거절한다
     * @param slidingWindowSize   실패율을 계산하는 최근 호출 수
     * @param minimumNumberOfCalls 이 수만큼 모이기 전에는 CB가 열리지 않는다. 창 크기를 넘을 수 없다
     * @param failureRateThreshold 이 비율(%) 이상 실패하면 OPEN
     * @param openDuration        OPEN을 유지하는 시간
     * @param halfOpenPermittedCalls HALF_OPEN에서 시험 삼아 허용하는 호출 수
     */
    public record Group(Integer maxConcurrentCalls, Integer slidingWindowSize, Integer minimumNumberOfCalls,
            Float failureRateThreshold, Duration openDuration, Integer halfOpenPermittedCalls) {
        public Group {
            // maxConcurrentCalls는 그룹별 기본값이 달라 여기서는 비워 둘 수 있고, 값이 있으면 양수여야 한다
            if (maxConcurrentCalls != null) positive(maxConcurrentCalls, "max-concurrent-calls");
            slidingWindowSize = positive(slidingWindowSize != null ? slidingWindowSize : 20, "sliding-window-size");
            minimumNumberOfCalls = positive(minimumNumberOfCalls != null ? minimumNumberOfCalls : slidingWindowSize,
                    "minimum-number-of-calls");
            failureRateThreshold = failureRateThreshold != null ? failureRateThreshold : 50f;
            openDuration = openDuration != null ? openDuration : Duration.ofSeconds(10);
            halfOpenPermittedCalls = positive(halfOpenPermittedCalls != null ? halfOpenPermittedCalls : 3,
                    "half-open-permitted-calls");
            if (failureRateThreshold <= 0 || failureRateThreshold > 100) {
                throw new IllegalArgumentException("failure-rate-threshold는 0 초과 100 이하여야 합니다");
            }
            // Note: Resilience4j는 창보다 큰 최소 호출 수를 조용히 창 크기로 줄여 쓴다. 설정값과 실제 동작이 달라지므로 막는다
            if (minimumNumberOfCalls > slidingWindowSize) {
                throw new IllegalArgumentException("minimum-number-of-calls는 sliding-window-size 이하여야 합니다");
            }
        }

        static Group unset() {
            return new Group(null, null, null, null, null, null);
        }

        Group withMaxConcurrentCalls(int defaultValue) {
            if (maxConcurrentCalls != null) return this;
            return new Group(defaultValue, slidingWindowSize, minimumNumberOfCalls, failureRateThreshold, openDuration,
                    halfOpenPermittedCalls);
        }

        private static int positive(int value, String name) {
            if (value <= 0) throw new IllegalArgumentException("payment.pg.protection." + name + "은 1 이상이어야 합니다");
            return value;
        }
    }
}
