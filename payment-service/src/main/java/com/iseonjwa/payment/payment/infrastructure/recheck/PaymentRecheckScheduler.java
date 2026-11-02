package com.iseonjwa.payment.payment.infrastructure.recheck;

import com.iseonjwa.payment.payment.application.PaymentRecheckService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 결제 재확인을 주기적으로 돌린다(events-and-reliability.md 6·7절). 한 주기에 최대 {@code maxPerTick}건을 한 건씩 처리하고,
 * 처리할 건이 없거나 PG 복구 허가를 못 얻으면 그 주기를 끝낸다.
 *
 * <p>Note: Spring {@code TaskScheduler} 빈을 만들지 않고 이 컴포넌트가 실행기를 직접 소유한다. 그런 빈을 하나 만들면 Boot의
 * 기본 스케줄러가 빠지고, 기본 스케줄러를 쓰는 Outbox 릴레이({@code @Scheduled})까지 그 빈으로 옮겨 가 재확인이 느려질 때 같이 멈춘다.
 * 스레드는 2개다. 두 번째는 환불 워커(ISE-39)가 쓸 자리다.
 *
 * <p>Note: {@code scheduleWithFixedDelay}는 작업이 예외로 끝나면 이후 실행을 모두 멈춘다. 한 주기의 예외는 여기서 잡아 ERROR로
 * 남기고 다음 주기를 계속한다. 잡지 못한 건은 임대(30초)가 끝나면 다시 대상이 된다.
 */
@Component
@ConditionalOnProperty(prefix = "payment.recheck", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(PaymentRecheckProperties.class)
public class PaymentRecheckScheduler implements SmartLifecycle {
    static final int THREADS = 2;
    private static final long STOP_WAIT_SECONDS = 5;

    private static final Logger log = LoggerFactory.getLogger(PaymentRecheckScheduler.class);

    private final PaymentRecheckService recheck;
    private final PaymentRecheckProperties properties;
    private ScheduledThreadPoolExecutor executor;

    public PaymentRecheckScheduler(PaymentRecheckService recheck, PaymentRecheckProperties properties) {
        this.recheck = recheck;
        this.properties = properties;
    }

    @Override
    public synchronized void start() {
        if (executor != null) return;
        executor = new ScheduledThreadPoolExecutor(THREADS, new RecoveryThreadFactory());
        executor.setRemoveOnCancelPolicy(true);
        var intervalMillis = properties.interval().toMillis();
        executor.scheduleWithFixedDelay(this::runTick, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
    }

    @Override
    public synchronized void stop() {
        if (executor == null) return;
        executor.shutdown();
        try {
            if (!executor.awaitTermination(STOP_WAIT_SECONDS, TimeUnit.SECONDS)) executor.shutdownNow();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
        executor = null;
    }

    @Override
    public synchronized boolean isRunning() {
        return executor != null;
    }

    void runTick() {
        try {
            tick();
        } catch (Exception exception) {
            log.error("결제 재확인 주기 실패, 다음 주기에 계속한다", exception);
        }
    }

    /** 한 주기. 처리한 건수를 돌려준다. */
    int tick() {
        var handled = 0;
        while (handled < properties.maxPerTick() && !Thread.currentThread().isInterrupted()) {
            if (!recheck.recheckNext()) break;
            handled++;
        }
        return handled;
    }

    private static final class RecoveryThreadFactory implements ThreadFactory {
        private final AtomicInteger sequence = new AtomicInteger();

        @Override
        public Thread newThread(Runnable task) {
            var thread = new Thread(task, "recovery-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }
}
