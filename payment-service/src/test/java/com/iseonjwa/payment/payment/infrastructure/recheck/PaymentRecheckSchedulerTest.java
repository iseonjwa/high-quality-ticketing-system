package com.iseonjwa.payment.payment.infrastructure.recheck;

import com.iseonjwa.payment.payment.application.PaymentRecheckService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Duration;
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

class PaymentRecheckSchedulerTest {
    private final PaymentRecheckService recheck = mock(PaymentRecheckService.class);

    private PaymentRecheckScheduler scheduler(int maxPerTick) {
        return new PaymentRecheckScheduler(recheck, new PaymentRecheckProperties(true, Duration.ofMillis(10), maxPerTick));
    }

    @Test
    void 한_주기에_최대_건수까지만_처리한다() {
        when(recheck.recheckNext()).thenReturn(true);

        assertEquals(3, scheduler(3).tick());

        verify(recheck, times(3)).recheckNext();
    }

    @Test
    void 처리할_건이_없거나_허가가_없으면_주기를_끝낸다() {
        when(recheck.recheckNext()).thenReturn(true, true, false);

        assertEquals(2, scheduler(20).tick());

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
    void enabled가_false면_스케줄러를_만들지_않는다() {
        new ApplicationContextRunner()
                .withBean(PaymentRecheckService.class, () -> recheck)
                .withUserConfiguration(PaymentRecheckScheduler.class)
                .withPropertyValues("payment.recheck.enabled=false")
                .run(context -> assertEquals(0, context.getBeansOfType(PaymentRecheckScheduler.class).size()));
    }

    @Test
    void enabled를_주지_않으면_스케줄러가_시작된다() {
        new ApplicationContextRunner()
                .withBean(PaymentRecheckService.class, () -> recheck)
                .withUserConfiguration(PaymentRecheckScheduler.class)
                .withPropertyValues("payment.recheck.interval=10s")
                .run(context -> assertTrue(context.getBean(PaymentRecheckScheduler.class).isRunning()));
    }
}
