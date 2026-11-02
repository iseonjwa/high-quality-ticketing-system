package com.iseonjwa.payment.infrastructure.recovery;

import com.iseonjwa.payment.payment.application.PaymentRecheckService;
import com.iseonjwa.payment.refund.application.RefundWorkerService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Duration;
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

class RecoverySchedulerTest {
    private final PaymentRecheckService recheck = mock(PaymentRecheckService.class);
    private final RefundWorkerService refundWorker = mock(RefundWorkerService.class);

    private RecoveryJob job(int maxPerTick) {
        return new RecoveryJob("recheck", Duration.ofMillis(10), maxPerTick, recheck::recheckNext);
    }

    private RecoveryScheduler scheduler(int maxPerTick) {
        return new RecoveryScheduler(List.of(job(maxPerTick)));
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
        var scheduler = new RecoveryScheduler(List.of(
                new RecoveryJob("recheck", Duration.ofMillis(10), 20, () -> record(names, "recheck", bothRan)),
                new RecoveryJob("refund", Duration.ofMillis(10), 20, () -> record(names, "refund", bothRan))));

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
        var scheduler = new RecoveryScheduler(List.of(
                new RecoveryJob("recheck", Duration.ofMillis(10), 20, () -> {
                    throw new IllegalStateException("일시 장애");
                }),
                new RecoveryJob("refund", Duration.ofMillis(10), 20, () -> {
                    otherRuns.countDown();
                    return false;
                })));

        scheduler.start();
        try {
            assertTrue(otherRuns.await(5, TimeUnit.SECONDS), "다른 작업이 이어지지 않았다");
        } finally {
            scheduler.stop();
        }
    }

    private static boolean record(ConcurrentHashMap<String, String> names, String job, CountDownLatch latch) {
        if (names.putIfAbsent(job, Thread.currentThread().getName()) == null) latch.countDown();
        return false;
    }

    private ApplicationContextRunner contextRunner() {
        return new ApplicationContextRunner()
                .withBean(PaymentRecheckService.class, () -> recheck)
                .withBean(RefundWorkerService.class, () -> refundWorker)
                .withUserConfiguration(RecoveryConfiguration.class);
    }

    @Test
    void 모든_작업이_꺼져_있으면_스케줄러를_만들지_않는다() {
        contextRunner()
                .withPropertyValues("payment.recheck.enabled=false", "payment.refund-worker.enabled=false")
                .run(context -> {
                    assertEquals(0, context.getBeansOfType(RecoveryJob.class).size());
                    assertEquals(0, context.getBeansOfType(RecoveryScheduler.class).size());
                });
    }

    @Test
    void enabled를_주지_않으면_두_작업이_등록되고_스케줄러가_시작된다() {
        contextRunner()
                .withPropertyValues("payment.recheck.interval=10s", "payment.refund-worker.interval=10s")
                .run(context -> {
                    assertEquals(2, context.getBeansOfType(RecoveryJob.class).size());
                    assertTrue(context.getBean(RecoveryScheduler.class).isRunning());
                });
    }

    @Test
    void 환불_워커만_꺼도_재확인_작업과_스케줄러는_남는다() {
        contextRunner()
                .withPropertyValues("payment.refund-worker.enabled=false", "payment.recheck.interval=10s")
                .run(context -> {
                    assertEquals(List.of("recheck"), context.getBeansOfType(RecoveryJob.class).values().stream()
                            .map(RecoveryJob::name).toList());
                    assertTrue(context.getBean(RecoveryScheduler.class).isRunning());
                });
    }

    @Test
    void 재확인만_꺼도_환불_워커_작업과_스케줄러는_남는다() {
        contextRunner()
                .withPropertyValues("payment.recheck.enabled=false", "payment.refund-worker.interval=10s")
                .run(context -> {
                    assertEquals(List.of("refund-worker"), context.getBeansOfType(RecoveryJob.class).values().stream()
                            .map(RecoveryJob::name).toList());
                    assertTrue(context.getBean(RecoveryScheduler.class).isRunning());
                });
    }
}
