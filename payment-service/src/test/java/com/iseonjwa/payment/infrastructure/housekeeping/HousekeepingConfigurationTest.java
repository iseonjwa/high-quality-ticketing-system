package com.iseonjwa.payment.infrastructure.housekeeping;

import com.iseonjwa.common.messaging.retention.RetentionCleanup;
import com.iseonjwa.payment.infrastructure.job.BackgroundJob;
import com.iseonjwa.payment.infrastructure.job.BackgroundJobScheduler;
import com.iseonjwa.payment.infrastructure.recovery.RecoveryConfiguration;
import com.iseonjwa.payment.observability.DbGaugeConfiguration;
import com.iseonjwa.payment.observability.PaymentMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import com.iseonjwa.payment.payment.application.PaymentRecheckService;
import com.iseonjwa.payment.refund.application.RefundWorkerService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Clock;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class HousekeepingConfigurationTest {
    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class))
                .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
                .withBean(PaymentMetrics.class, () -> new PaymentMetrics(new SimpleMeterRegistry()))
                .withBean(PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class))
                .withBean("commonAuditingClock", Clock.class, Clock::systemUTC)
                .withBean(PaymentRecheckService.class, () -> mock(PaymentRecheckService.class))
                .withBean(RefundWorkerService.class, () -> mock(RefundWorkerService.class))
                .withUserConfiguration(HousekeepingConfiguration.class, RecoveryConfiguration.class,
                        DbGaugeConfiguration.class);
    }

    @Test
    void enabled를_주지_않으면_정리_작업_셋이_housekeeping_실행기에만_올라간다() {
        runner().withPropertyValues("payment.housekeeping.interval=1h", "payment.recheck.interval=1h",
                "payment.refund-worker.interval=1h").run(context -> {
            var housekeeping = context.getBean("housekeepingScheduler", BackgroundJobScheduler.class);
            var recovery = context.getBean("recoveryScheduler", BackgroundJobScheduler.class);

            assertEquals(List.of("gauge-refresh", "idempotency-cleanup", "outbox-cleanup", "processed-events-cleanup"),
                    housekeeping.jobNames().stream().sorted().toList());
            assertEquals(List.of("recheck", "refund-worker"), recovery.jobNames().stream().sorted().toList());
            assertTrue(housekeeping.isRunning());
            assertTrue(recovery.isRunning());
            assertNotNull(context.getBean(RetentionCleanup.class));
        });
    }

    @Test
    void enabled가_false면_정리_작업을_등록하지_않고_실행기도_만들지_않는다() {
        runner().withPropertyValues("payment.housekeeping.enabled=false", "payment.recheck.interval=1h",
                "payment.refund-worker.interval=1h").run(context -> {
            var housekeeping = context.getBean("housekeepingScheduler", BackgroundJobScheduler.class);

            // 정리 작업만 꺼진다. 게이지 갱신은 따로 켜고 끈다
            assertEquals(List.of("gauge-refresh"), housekeeping.jobNames());
            assertTrue(housekeeping.isRunning());
            assertEquals(3, context.getBeansOfType(BackgroundJob.class).size());
        });
    }

    @Test
    void 정리와_게이지_갱신을_모두_끄면_작업이_없어_실행기를_만들지_않는다() {
        runner().withPropertyValues("payment.housekeeping.enabled=false", "payment.metrics.gauge-refresh.enabled=false",
                "payment.recheck.interval=1h", "payment.refund-worker.interval=1h").run(context -> {
            var housekeeping = context.getBean("housekeepingScheduler", BackgroundJobScheduler.class);

            assertEquals(List.of(), housekeeping.jobNames());
            assertFalse(housekeeping.isRunning());
            assertEquals(2, context.getBeansOfType(BackgroundJob.class).size());
            assertTrue(Thread.getAllStackTraces().keySet().stream()
                    .noneMatch(thread -> thread.getName().startsWith("housekeeping-")));
        });
    }

    @Test
    void 게이지_갱신은_기동_직후_바로_돌고_housekeeping_실행기에만_올라간다() {
        runner().withPropertyValues("payment.housekeeping.interval=1h", "payment.recheck.interval=1h",
                "payment.refund-worker.interval=1h").run(context -> {
            var job = context.getBeansOfType(BackgroundJob.class).values().stream()
                    .filter(candidate -> candidate.name().equals("gauge-refresh")).findFirst().orElseThrow();

            assertEquals(Duration.ZERO, job.initialDelay());
            assertEquals(Duration.ofSeconds(15), job.interval());
            assertEquals(1, job.maxPerTick());
            var recovery = context.getBean("recoveryScheduler", BackgroundJobScheduler.class);
            assertFalse(recovery.jobNames().contains("gauge-refresh"));
        });
    }

    @Test
    void 게이지_갱신_enabled가_false면_작업을_등록하지_않지만_게이지는_남는다() {
        runner().withPropertyValues("payment.metrics.gauge-refresh.enabled=false", "payment.housekeeping.interval=1h",
                "payment.recheck.interval=1h", "payment.refund-worker.interval=1h").run(context -> {
            var housekeeping = context.getBean("housekeepingScheduler", BackgroundJobScheduler.class);

            assertFalse(housekeeping.jobNames().contains("gauge-refresh"));
            assertEquals(3, housekeeping.jobNames().size());
            assertNotNull(context.getBean(com.iseonjwa.payment.observability.DbAggregateGauges.class));
        });
    }

    @Test
    void 게이지_갱신_간격을_바꿀_수_있고_0이면_기동이_실패한다() {
        runner().withPropertyValues("payment.metrics.gauge-refresh.interval=30s", "payment.housekeeping.interval=1h",
                "payment.recheck.interval=1h", "payment.refund-worker.interval=1h").run(context -> {
            var job = context.getBeansOfType(BackgroundJob.class).values().stream()
                    .filter(candidate -> candidate.name().equals("gauge-refresh")).findFirst().orElseThrow();
            assertEquals(Duration.ofSeconds(30), job.interval());
        });
        runner().withPropertyValues("payment.metrics.gauge-refresh.interval=0s")
                .run(context -> assertNotNull(context.getStartupFailure()));
    }

    @Test
    void 보관_기간이_모자라면_기동이_실패하고_메시지에_세_값이_있다() {
        runner().withPropertyValues("payment.housekeeping.processed-events-retention=5d",
                "payment.housekeeping.topic-retention=7d", "payment.housekeeping.dlq-retention=3d").run(context -> {
            var failure = context.getStartupFailure();
            assertNotNull(failure);
            var message = rootMessages(failure);
            assertTrue(message.contains("PT120H") && message.contains("PT168H") && message.contains("PT72H"), message);
        });
    }

    @Test
    void enabled가_false여도_보관_기간_검증은_한다() {
        runner().withPropertyValues("payment.housekeeping.enabled=false",
                "payment.housekeeping.processed-events-retention=7d", "payment.housekeeping.topic-retention=7d")
                .run(context -> assertNotNull(context.getStartupFailure()));
    }

    private static String rootMessages(Throwable failure) {
        var messages = new StringBuilder();
        for (var cause = failure; cause != null; cause = cause.getCause()) messages.append(cause.getMessage()).append('\n');
        return messages.toString();
    }
}
