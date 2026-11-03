package com.iseonjwa.payment.infrastructure.housekeeping;

import com.iseonjwa.common.messaging.retention.RetentionCleanup;
import com.iseonjwa.payment.infrastructure.job.BackgroundJob;
import com.iseonjwa.payment.infrastructure.job.BackgroundJobScheduler;
import com.iseonjwa.payment.infrastructure.recovery.RecoveryConfiguration;
import com.iseonjwa.payment.payment.application.PaymentRecheckService;
import com.iseonjwa.payment.refund.application.RefundWorkerService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Clock;
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
                .withBean(PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class))
                .withBean("commonAuditingClock", Clock.class, Clock::systemUTC)
                .withBean(PaymentRecheckService.class, () -> mock(PaymentRecheckService.class))
                .withBean(RefundWorkerService.class, () -> mock(RefundWorkerService.class))
                .withUserConfiguration(HousekeepingConfiguration.class, RecoveryConfiguration.class);
    }

    @Test
    void enabled를_주지_않으면_정리_작업_셋이_housekeeping_실행기에만_올라간다() {
        runner().withPropertyValues("payment.housekeeping.interval=1h", "payment.recheck.interval=1h",
                "payment.refund-worker.interval=1h").run(context -> {
            var housekeeping = context.getBean("housekeepingScheduler", BackgroundJobScheduler.class);
            var recovery = context.getBean("recoveryScheduler", BackgroundJobScheduler.class);

            assertEquals(List.of("outbox-cleanup", "processed-events-cleanup", "idempotency-cleanup"),
                    housekeeping.jobNames().stream().sorted(java.util.Comparator.comparing(name ->
                            List.of("outbox-cleanup", "processed-events-cleanup", "idempotency-cleanup").indexOf(name)))
                            .toList());
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

            assertEquals(List.of(), housekeeping.jobNames());
            assertFalse(housekeeping.isRunning());
            assertEquals(2, context.getBeansOfType(BackgroundJob.class).size());
            assertTrue(Thread.getAllStackTraces().keySet().stream()
                    .noneMatch(thread -> thread.getName().startsWith("housekeeping-")));
        });
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
