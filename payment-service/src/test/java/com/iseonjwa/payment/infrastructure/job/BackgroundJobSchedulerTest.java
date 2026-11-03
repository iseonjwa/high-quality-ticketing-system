package com.iseonjwa.payment.infrastructure.job;

import com.iseonjwa.payment.infrastructure.recovery.RecoveryConfiguration;
import com.iseonjwa.payment.payment.application.PaymentRecheckService;
import com.iseonjwa.payment.refund.application.RefundWorkerService;
import com.iseonjwa.payment.observability.PaymentMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BackgroundJobSchedulerTest {
    private final PaymentRecheckService recheck = mock(PaymentRecheckService.class);
    private final RefundWorkerService refundWorker = mock(RefundWorkerService.class);

    private BackgroundJob job(int maxPerTick) {
        return new BackgroundJob("recheck", Duration.ofMillis(10), maxPerTick, recheck::recheckNext);
    }

    private BackgroundJobScheduler scheduler(int maxPerTick) {
        return new BackgroundJobScheduler("recovery", 2, List.of(job(maxPerTick)), new PaymentMetrics(new SimpleMeterRegistry()), Clock.systemUTC());
    }

    @Test
    void 한_주기에_최대_건수까지만_처리한다() {
        when(recheck.recheckNext()).thenReturn(true);

        assertEquals(3, scheduler(3).tick(job(3)));

        verify(recheck, times(3)).recheckNext();
    }

    @Test
    void 처리할_건이_없거나_허가가_없으면_주기를_끝낸다() {
        when(recheck.recheckNext()).thenReturn(true, true, false);

        assertEquals(2, scheduler(20).tick(job(20)));

        verify(recheck, times(3)).recheckNext();
    }

    @Test
    void 한_주기가_예외로_끝나도_다음_주기를_계속_돈다() throws Exception {
        var calls = new AtomicInteger();
        var recovered = new CountDownLatch(1);
        when(recheck.recheckNext()).thenAnswer(invocation -> {
            if (calls.incrementAndGet() == 1) throw new IllegalStateException("일시 장애");
            recovered.countDown();
            return false;
        });
        var scheduler = scheduler(20);

        scheduler.start();
        try {
            assertTrue(recovered.await(5, TimeUnit.SECONDS), "예외 뒤 주기가 이어지지 않았다");
        } finally {
            scheduler.stop();
        }
    }

    @Test
    void 한_주기에서_Error가_나도_다음_주기가_돈다() throws Exception {
        var calls = new AtomicInteger();
        var recovered = new CountDownLatch(1);
        when(recheck.recheckNext()).thenAnswer(invocation -> {
            if (calls.incrementAndGet() == 1) throw new AssertionError("Error 시뮬레이션");
            recovered.countDown();
            return false;
        });
        var scheduler = scheduler(20);

        scheduler.start();
        try {
            assertTrue(recovered.await(5, TimeUnit.SECONDS), "Error 뒤 주기가 이어지지 않았다");
        } finally {
            scheduler.stop();
        }
    }

    @Test
    void 시작하면_recovery_스레드에서_돌고_멈추면_종료한다() throws Exception {
        var threadName = new AtomicReference<String>();
        var ran = new CountDownLatch(1);
        when(recheck.recheckNext()).thenAnswer(invocation -> {
            threadName.set(Thread.currentThread().getName());
            ran.countDown();
            return false;
        });
        var scheduler = scheduler(20);

        scheduler.start();
        assertTrue(scheduler.isRunning());
        assertTrue(ran.await(5, TimeUnit.SECONDS));
        scheduler.stop();

        assertTrue(threadName.get().startsWith("recovery-"), threadName.get());
        assertFalse(scheduler.isRunning());
    }

    @Test
    void 두_작업이_등록되면_둘_다_recovery_스레드에서_돈다() throws Exception {
        var names = new ConcurrentHashMap<String, String>();
        var bothRan = new CountDownLatch(2);
        var scheduler = new BackgroundJobScheduler("recovery", 2, List.of(
                new BackgroundJob("recheck", Duration.ofMillis(10), 20, () -> record(names, "recheck", bothRan)),
                new BackgroundJob("refund", Duration.ofMillis(10), 20, () -> record(names, "refund", bothRan))), new PaymentMetrics(new SimpleMeterRegistry()), Clock.systemUTC());

        scheduler.start();
        try {
            assertTrue(bothRan.await(5, TimeUnit.SECONDS), "두 작업이 모두 돌지 않았다: " + names);
        } finally {
            scheduler.stop();
        }

        assertTrue(names.get("recheck").startsWith("recovery-"), names.get("recheck"));
        assertTrue(names.get("refund").startsWith("recovery-"), names.get("refund"));
    }

    @Test
    void 한_작업이_예외를_던져도_다른_작업은_계속_돈다() throws Exception {
        var otherRuns = new CountDownLatch(3);
        var scheduler = new BackgroundJobScheduler("recovery", 2, List.of(
                new BackgroundJob("recheck", Duration.ofMillis(10), 20, () -> {
                    throw new IllegalStateException("일시 장애");
                }),
                new BackgroundJob("refund", Duration.ofMillis(10), 20, () -> {
                    otherRuns.countDown();
                    return false;
                })), new PaymentMetrics(new SimpleMeterRegistry()), Clock.systemUTC());

        scheduler.start();
        try {
            assertTrue(otherRuns.await(5, TimeUnit.SECONDS), "다른 작업이 이어지지 않았다");
        } finally {
            scheduler.stop();
        }
    }

    @Test
    void 이름_접두사가_다른_두_실행기는_서로_다른_스레드에서_돈다() throws Exception {
        var names = new ConcurrentHashMap<String, String>();
        var bothRan = new CountDownLatch(2);
        var recovery = new BackgroundJobScheduler("recovery", 2, List.of(
                new BackgroundJob("recheck", Duration.ofMillis(10), 20, () -> record(names, "recheck", bothRan))), new PaymentMetrics(new SimpleMeterRegistry()), Clock.systemUTC());
        var housekeeping = new BackgroundJobScheduler("housekeeping", 1, List.of(
                new BackgroundJob("cleanup", Duration.ofMillis(10), 20, () -> record(names, "cleanup", bothRan))), new PaymentMetrics(new SimpleMeterRegistry()), Clock.systemUTC());

        recovery.start();
        housekeeping.start();
        try {
            assertTrue(bothRan.await(5, TimeUnit.SECONDS), "두 작업이 모두 돌지 않았다: " + names);
        } finally {
            recovery.stop();
            housekeeping.stop();
        }

        assertTrue(names.get("recheck").matches("recovery-\\d+"), names.get("recheck"));
        assertTrue(names.get("cleanup").matches("housekeeping-\\d+"), names.get("cleanup"));
    }

    @Test
    void 주기가_예외_없이_끝나면_마지막_성공_시각을_갱신한다_할_일이_없어도_성공이다() {
        var meters = new SimpleMeterRegistry();
        var clock = new MutableClock(Instant.parse("2026-11-03T00:00:00Z"));
        var scheduler = new BackgroundJobScheduler("recovery", 2, List.of(job(20)), new PaymentMetrics(meters), clock);
        when(recheck.recheckNext()).thenReturn(false);
        assertEquals(clock.instant().getEpochSecond(), lastSuccess(meters), "등록 시각으로 시작한다");

        clock.advance(Duration.ofSeconds(90));
        scheduler.runTick(job(20));

        assertEquals(clock.instant().getEpochSecond(), lastSuccess(meters));
        assertEquals(0, failures(meters));
    }

    @Test
    void 주기가_예외나_Error로_끝나면_실패_수만_올리고_마지막_성공_시각은_그대로다() {
        var meters = new SimpleMeterRegistry();
        var clock = new MutableClock(Instant.parse("2026-11-03T00:00:00Z"));
        var scheduler = new BackgroundJobScheduler("recovery", 2, List.of(job(20)), new PaymentMetrics(meters), clock);
        var started = clock.instant().getEpochSecond();
        when(recheck.recheckNext()).thenThrow(new IllegalStateException("일시 장애")).thenThrow(new AssertionError("Error"));
        clock.advance(Duration.ofSeconds(90));

        scheduler.runTick(job(20));
        scheduler.runTick(job(20));

        assertEquals(2, failures(meters));
        assertEquals(started, lastSuccess(meters));
    }

    @Test
    void 작업별로_따로_센다() {
        var meters = new SimpleMeterRegistry();
        var clock = new MutableClock(Instant.parse("2026-11-03T00:00:00Z"));
        var failing = new BackgroundJob("refund-worker", Duration.ofMillis(10), 20, () -> {
            throw new IllegalStateException("일시 장애");
        });
        var scheduler = new BackgroundJobScheduler("recovery", 2, List.of(job(20), failing),
                new PaymentMetrics(meters), clock);
        when(recheck.recheckNext()).thenReturn(false);
        clock.advance(Duration.ofSeconds(10));

        scheduler.runTick(job(20));
        scheduler.runTick(failing);

        assertEquals(0, meters.get("payment.job.failures").tag("job", "recheck").counter().count());
        assertEquals(1, meters.get("payment.job.failures").tag("job", "refund-worker").counter().count());
        assertEquals(clock.instant().getEpochSecond(), lastSuccess(meters));
    }

    private static double lastSuccess(SimpleMeterRegistry meters) {
        return meters.get("payment.job.last.success.timestamp").tag("job", "recheck").gauge().value();
    }

    private static double failures(SimpleMeterRegistry meters) {
        return meters.get("payment.job.failures").tag("job", "recheck").counter().count();
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private static boolean record(ConcurrentHashMap<String, String> names, String job, CountDownLatch latch) {
        if (names.putIfAbsent(job, Thread.currentThread().getName()) == null) latch.countDown();
        return false;
    }

    private ApplicationContextRunner contextRunner() {
        return new ApplicationContextRunner()
                .withBean(PaymentMetrics.class, () -> new PaymentMetrics(new SimpleMeterRegistry()))
                .withBean("commonAuditingClock", Clock.class, Clock::systemUTC)
                .withBean(PaymentRecheckService.class, () -> recheck)
                .withBean(RefundWorkerService.class, () -> refundWorker)
                .withUserConfiguration(RecoveryConfiguration.class);
    }

    @Test
    void 작업이_없으면_실행기를_만들지_않고_스레드를_띄우지_않는다() {
        contextRunner()
                .withPropertyValues("payment.recheck.enabled=false", "payment.refund-worker.enabled=false")
                .run(context -> {
                    assertEquals(0, context.getBeansOfType(BackgroundJob.class).size());
                    var scheduler = context.getBean("recoveryScheduler", BackgroundJobScheduler.class);
                    assertFalse(scheduler.isRunning());
                    assertTrue(Thread.getAllStackTraces().keySet().stream()
                            .noneMatch(thread -> thread.getName().startsWith("recovery-")));
                });
    }

    @Test
    void enabled를_주지_않으면_두_작업이_등록되고_스케줄러가_시작된다() {
        contextRunner()
                .withPropertyValues("payment.recheck.interval=10s", "payment.refund-worker.interval=10s")
                .run(context -> {
                    assertEquals(2, context.getBeansOfType(BackgroundJob.class).size());
                    assertTrue(context.getBean("recoveryScheduler", BackgroundJobScheduler.class).isRunning());
                });
    }

    @Test
    void 환불_워커만_꺼도_재확인_작업과_스케줄러는_남는다() {
        contextRunner()
                .withPropertyValues("payment.refund-worker.enabled=false", "payment.recheck.interval=10s")
                .run(context -> {
                    assertEquals(List.of("recheck"), context.getBeansOfType(BackgroundJob.class).values().stream()
                            .map(BackgroundJob::name).toList());
                    assertTrue(context.getBean("recoveryScheduler", BackgroundJobScheduler.class).isRunning());
                });
    }

    @Test
    void 재확인만_꺼도_환불_워커_작업과_스케줄러는_남는다() {
        contextRunner()
                .withPropertyValues("payment.recheck.enabled=false", "payment.refund-worker.interval=10s")
                .run(context -> {
                    assertEquals(List.of("refund-worker"), context.getBeansOfType(BackgroundJob.class).values().stream()
                            .map(BackgroundJob::name).toList());
                    assertTrue(context.getBean("recoveryScheduler", BackgroundJobScheduler.class).isRunning());
                });
    }
}
