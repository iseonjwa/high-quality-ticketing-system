package com.iseonjwa.payment.observability;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * DB 집계 Gauge 갱신 설정(observability.md 1절). 정리 작업의 {@code payment.housekeeping.enabled}와 따로 켜고 끈다.
 *
 * @param enabled  false면 {@code gauge-refresh} 작업을 등록하지 않는다(테스트는 갱신 메서드를 직접 부른다)
 * @param interval 한 갱신이 끝난 뒤 다음 갱신까지의 간격
 */
@ConfigurationProperties(prefix = "payment.metrics.gauge-refresh")
public record GaugeRefreshProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("15s") Duration interval) {
    public GaugeRefreshProperties {
        if (interval.isZero() || interval.isNegative()) {
            throw new IllegalArgumentException("payment.metrics.gauge-refresh.interval은 0보다 커야 합니다");
        }
    }
}
