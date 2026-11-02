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
    // 진행 중인 한 건의 최악 시간: claim 트랜잭션 + PG 조회 2.5초 + 늦은 승인 방지 2.5초 + 전이 트랜잭션, 약 7초. 그 두 배 가까이 기다린다
    private static final long STOP_WAIT_SECONDS = 15;

    private static final Logger log = LoggerFactory.getLogger(PaymentRecheckScheduler.class);

    private final PaymentRecheckService recheck;
    private final PaymentRecheckProperties properties;
    private ScheduledThreadPoolExecutor executor;
    private volatile boolean stopping;

    public PaymentRecheckScheduler(PaymentRecheckService recheck, PaymentRecheckProperties properties) {
        this.recheck = recheck;
        this.properties = properties;
    }

    @Override
    public synchronized void start() {
        if (executor != null) return;
        stopping = false;
        executor = new ScheduledThreadPoolExecutor(THREADS, new RecoveryThreadFactory());
        executor.setRemoveOnCancelPolicy(true);
        var intervalMillis = properties.interval().toMillis();
        executor.scheduleWithFixedDelay(this::runTick, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
    }

    @Override
    public synchronized void stop() {
        if (executor == null) return;
        // 새 claim을 멈춘다. 진행 중인 한 건은 shutdown 뒤에도 끝까지 처리한다
        stopping = true;
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
            // TODO(ISE-41): payment_job_last_success_timestamp{job=recheck} 갱신
        } catch (Throwable throwable) {
            // Note: ScheduledThreadPoolExecutor는 주기 작업이 예외로 끝나면 다음 실행을 예약하지 않고, 예외는 아무도 읽지 않는
            // Future에만 남는다. Error까지 여기서 잡아 로그를 남기고 다음 주기를 계속한다(Spring @Scheduled의 LOG_AND_SUPPRESS와 같다)
            log.error("결제 재확인 주기 실패, 다음 주기에 계속한다", throwable);
        }
    }

    /** 한 주기. 처리한 건수를 돌려준다. */
    int tick() {
        var handled = 0;
        while (handled < properties.maxPerTick() && !stopping && !Thread.currentThread().isInterrupted()) {
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
