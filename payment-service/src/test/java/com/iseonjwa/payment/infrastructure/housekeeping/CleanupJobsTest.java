package com.iseonjwa.payment.infrastructure.housekeeping;

import com.iseonjwa.payment.infrastructure.job.BackgroundJobScheduler;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CleanupJobsTest {
    private static HousekeepingProperties properties(int batchSize, int maxBatches) {
        return new HousekeepingProperties(true, Duration.ofMinutes(10), batchSize, maxBatches, Duration.ZERO,
                Duration.ofDays(7), Duration.ofDays(14), Duration.ofDays(7), Duration.ofDays(7));
    }

    @Test
    void 가득_지우면_이어서_배치를_돌고_덜_지우면_주기를_끝낸다() {
        var calls = new AtomicInteger();
        // 배치 크기 10: 10, 10, 4건을 지운다
        var job = CleanupJobs.create("test", properties(10, 100), () -> new int[] {10, 10, 4}[calls.getAndIncrement()]);

        assertEquals(2, scheduler(job).tick(job));
        assertEquals(3, calls.get());
    }

    @Test
    void 잠금을_못_잡아_0이면_바로_주기를_끝낸다() {
        var calls = new AtomicInteger();
        var job = CleanupJobs.create("test", properties(10, 100), () -> {
            calls.incrementAndGet();
            return 0;
        });

        assertEquals(0, scheduler(job).tick(job));
        assertEquals(1, calls.get());
    }

    @Test
    void 한_주기의_배치_수는_상한에서_멈춘다() {
        var calls = new AtomicInteger();
        var job = CleanupJobs.create("test", properties(10, 3), () -> {
            calls.incrementAndGet();
            return 10;
        });

        assertEquals(3, scheduler(job).tick(job));
        assertEquals(3, calls.get());
    }

    @Test
    void 배치_사이_쉬는_중_인터럽트되면_주기를_끝내고_인터럽트_상태를_남긴다() {
        var job = CleanupJobs.create("test", new HousekeepingProperties(true, Duration.ofMinutes(10), 10, 100,
                Duration.ofSeconds(30), Duration.ofDays(7), Duration.ofDays(14), Duration.ofDays(7), Duration.ofDays(7)),
                () -> 10);
        Thread.currentThread().interrupt();
        try {
            assertFalse(job.next().getAsBoolean());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    private static BackgroundJobScheduler scheduler(com.iseonjwa.payment.infrastructure.job.BackgroundJob job) {
        return new BackgroundJobScheduler("housekeeping", 1, List.of(job));
    }
}
