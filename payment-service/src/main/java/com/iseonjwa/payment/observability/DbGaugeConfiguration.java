package com.iseonjwa.payment.observability;

import com.iseonjwa.payment.infrastructure.housekeeping.HousekeepingConfiguration;
import com.iseonjwa.payment.infrastructure.job.BackgroundJob;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.Duration;

/**
 * DB 집계 Gauge와 그것을 갱신하는 {@code gauge-refresh} 작업을 등록한다. 작업은 {@code housekeeping} 실행기에 올라가
 * 기동 직후 바로 한 번 돌고(첫 지연 0) 이후 설정한 간격으로 돈다(events-and-reliability.md 7절). 갱신이 예외로 끝나면
 * 실행기가 {@code payment.job.failures{job="gauge-refresh"}}를 올리고 이전 값은 그대로 남는다.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(GaugeRefreshProperties.class)
public class DbGaugeConfiguration {
    public static final String JOB_NAME = "gauge-refresh";

    // 작업이 꺼져 있어도 Gauge는 등록한다(값은 0). 테스트는 refresh()를 직접 부른다
    @Bean
    DbAggregateGauges dbAggregateGauges(MeterRegistry registry, JdbcTemplate jdbc,
            @Qualifier("commonAuditingClock") Clock clock) {
        return new DbAggregateGauges(registry, jdbc, clock);
    }

    @Bean
    @Qualifier(HousekeepingConfiguration.QUALIFIER)
    @ConditionalOnProperty(prefix = "payment.metrics.gauge-refresh", name = "enabled", havingValue = "true",
            matchIfMissing = true)
    BackgroundJob gaugeRefreshJob(DbAggregateGauges gauges, GaugeRefreshProperties properties) {
        // 한 주기에 한 번 갱신하고 끝낸다(false). 첫 지연 0으로 기동 직후 바로 돈다
        return new BackgroundJob(JOB_NAME, properties.interval(), Duration.ZERO, 1, () -> {
            gauges.refresh();
            return false;
        });
    }
}
