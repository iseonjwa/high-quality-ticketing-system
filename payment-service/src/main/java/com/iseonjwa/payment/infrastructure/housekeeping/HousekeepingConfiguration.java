package com.iseonjwa.payment.infrastructure.housekeeping;

import com.iseonjwa.common.messaging.retention.RetentionCleanup;
import com.iseonjwa.payment.observability.PaymentMetrics;
import com.iseonjwa.payment.infrastructure.job.BackgroundJob;
import com.iseonjwa.payment.infrastructure.job.BackgroundJobScheduler;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Clock;

/**
 * 보관 정리 작업을 {@code housekeeping} 실행기(스레드 1개)에 등록한다(events-and-reliability.md 7·8절).
 * 설정 클래스({@link HousekeepingProperties})는 {@code enabled}와 상관없이 만들어져 보관 기간 검증은 늘 한다.
 * {@code enabled=false}면 작업을 등록하지 않고, 작업이 없으면 스케줄러가 실행기를 만들지 않는다.
 *
 * <p>Note: {@link RetentionCleanup}은 common이 빈으로 자동 등록하지 않는 클래스라 여기서 직접 등록한다. 다른 서비스에
 * 정리가 저절로 켜지지 않게 하려는 것이다.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(HousekeepingProperties.class)
public class HousekeepingConfiguration {
    public static final String QUALIFIER = "housekeeping";
    static final int THREADS = 1;

    @Bean
    RetentionCleanup retentionCleanup(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        return new RetentionCleanup(jdbc, transactionManager);
    }

    @Bean
    IdempotencyRecordCleanup idempotencyRecordCleanup(RetentionCleanup cleanup) {
        return new IdempotencyRecordCleanup(cleanup);
    }

    @Bean
    @Qualifier(QUALIFIER)
    @ConditionalOnProperty(prefix = "payment.housekeeping", name = "enabled", havingValue = "true", matchIfMissing = true)
    BackgroundJob outboxCleanupJob(RetentionCleanup cleanup, HousekeepingProperties properties,
            @Qualifier("commonAuditingClock") Clock clock) {
        return CleanupJobs.create("outbox-cleanup", properties, () -> cleanup.deleteSentOutbox(
                clock.instant().minus(properties.outboxSentRetention()), properties.batchSize()));
    }

    @Bean
    @Qualifier(QUALIFIER)
    @ConditionalOnProperty(prefix = "payment.housekeeping", name = "enabled", havingValue = "true", matchIfMissing = true)
    BackgroundJob processedEventsCleanupJob(RetentionCleanup cleanup, HousekeepingProperties properties,
            @Qualifier("commonAuditingClock") Clock clock) {
        return CleanupJobs.create("processed-events-cleanup", properties, () -> cleanup.deleteProcessedEvents(
                clock.instant().minus(properties.processedEventsRetention()), properties.batchSize()));
    }

    @Bean
    @Qualifier(QUALIFIER)
    @ConditionalOnProperty(prefix = "payment.housekeeping", name = "enabled", havingValue = "true", matchIfMissing = true)
    BackgroundJob idempotencyCleanupJob(IdempotencyRecordCleanup cleanup, HousekeepingProperties properties,
            @Qualifier("commonAuditingClock") Clock clock) {
        return CleanupJobs.create("idempotency-cleanup", properties,
                () -> cleanup.deleteExpired(clock.instant(), properties.batchSize()));
    }

    @Bean
    BackgroundJobScheduler housekeepingScheduler(@Qualifier(QUALIFIER) ObjectProvider<BackgroundJob> jobs,
            PaymentMetrics metrics, @Qualifier("commonAuditingClock") Clock clock) {
        return new BackgroundJobScheduler("housekeeping", THREADS, jobs.orderedStream().toList(), metrics, clock);
    }
}
