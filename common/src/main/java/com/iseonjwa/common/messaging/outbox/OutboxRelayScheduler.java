package com.iseonjwa.common.messaging.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * Outbox 릴레이를 주기적으로 돌린다(events-and-reliability.md 3·7절). 한 주기에 최대 {@link #MAX_ROUNDS}번 집어 보내고,
 * 한 번에 가득 집어 모두 보냈으면 쉬지 않고 이어서 비운다.
 *
 * <p>Note: Spring {@code TaskScheduler}나 {@code ScheduledExecutorService} 빈을 만들지 않고 이 컴포넌트가 단일 스레드
 * 실행기({@code outbox-relay})를 직접 소유한다. 그런 빈을 하나 만들면 Boot의 기본 스케줄러가 빠지고, 기본 스케줄러를 쓰는
 * 다른 {@code @Scheduled} 작업이 그 빈으로 옮겨 가 섞인다. 릴레이가 {@code @Scheduled}를 쓰지 않으므로 공통 설정은
 * {@code @EnableScheduling}도 켜지 않는다. 전용 스레드라서 PG 재조회 같은 느린 작업에 릴레이가 밀리지 않는다.
 *
 * <p>Note: {@code scheduleWithFixedDelay}는 작업이 예외로 끝나면 이후 실행을 모두 멈추고 예외는 아무도 읽지 않는
 * Future에만 남는다. 한 주기의 예외와 Error는 여기서 잡아 ERROR로 남기고 다음 주기를 계속한다. 브로커가 죽어 일부라도
 * 못 보냈으면 이어 가지 않는다. 라운드마다 마감까지 기다리며 스레드를 독점하기 때문이다.
 *
 * <p>Note: 종료할 때는 새 라운드를 먼저 멈추고({@code stopping}) 진행 중인 한 번의 실행을 기다린다. 그 실행은 브로커
 * 확인을 최대 전송 시한까지 기다리므로 종료 대기는 전송 시한보다 길어야 한다. 기다려도 끝나지 않으면 인터럽트하고,
 * 확인을 못 받은 행은 임대가 끝난 뒤 다시 집힌다(중복 가능, 소비자가 멱등).
 */
public class OutboxRelayScheduler implements SmartLifecycle {
    // 한 번에 가득 집어 모두 보냈으면 쉬지 않고 이어서 비운다. 한 주기에 비우는 상한을 둬 한 주기가 끝없이 길어지지 않게 한다
    static final int MAX_ROUNDS = 10;
    static final String THREAD_NAME = "outbox-relay";

    private static final Logger log = LoggerFactory.getLogger(OutboxRelayScheduler.class);

    private final OutboxRelay relay;
    private final Duration interval;
    private final Duration stopWait;
    private final OutboxMetrics metrics;
    private final Clock clock;
    private ScheduledThreadPoolExecutor executor;
    private volatile boolean stopping;

    /**
     * @param interval 한 주기가 끝나고 다음 주기를 시작하기까지의 간격
     * @param stopWait 종료할 때 진행 중인 실행을 기다리는 시간. 전송 시한보다 길어야 한다
     */
    public OutboxRelayScheduler(OutboxRelay relay, Duration interval, Duration stopWait) {
        this(relay, interval, stopWait, OutboxMetrics.disabled(), Clock.systemUTC());
    }

    /**
     * @param metrics 릴레이 주기의 마지막 성공 시각과 실패 수를 남기는 곳
     * @param clock   마지막 성공 시각을 정하는 시계
     */
    public OutboxRelayScheduler(OutboxRelay relay, Duration interval, Duration stopWait, OutboxMetrics metrics,
            Clock clock) {
        if (interval.isNegative() || interval.isZero()) {
            throw new IllegalArgumentException("messaging.outbox.interval은 0보다 커야 합니다");
        }
        this.relay = relay;
        this.interval = interval;
        this.stopWait = stopWait;
        this.metrics = metrics;
        this.clock = clock;
    }

    @Override
    public synchronized void start() {
        if (executor != null) return;
        stopping = false;
        executor = new ScheduledThreadPoolExecutor(1, new RelayThreadFactory());
        executor.setRemoveOnCancelPolicy(true);
        executor.scheduleWithFixedDelay(this::runTick, interval.toMillis(), interval.toMillis(), TimeUnit.MILLISECONDS);
    }

    @Override
    public synchronized void stop() {
        if (executor == null) return;
        stopping = true;
        executor.shutdown();
        try {
            if (!executor.awaitTermination(stopWait.toMillis(), TimeUnit.MILLISECONDS)) executor.shutdownNow();
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
            relayRounds();
            // Note: 전송이 실패해도 라운드는 정상으로 끝나므로 여기 도달한다. 이 시각은 릴레이가 돌고 있다는 신호일 뿐
            // 브로커 장애는 알려 주지 않는다(OutboxMetrics 참고)
            metrics.relaySucceeded(clock.instant());
        } catch (Throwable throwable) {
            metrics.relayFailed();
            log.error("Outbox 릴레이 주기 실패, 다음 주기에 계속한다", throwable);
        }
    }

    void relayRounds() {
        for (var round = 0; round < MAX_ROUNDS && !stopping && !Thread.currentThread().isInterrupted(); round++) {
            var result = relay.relayOnce();
            if (result.claimed() < relay.batchSize() || result.sent() < result.claimed()) return;
        }
    }

    private static final class RelayThreadFactory implements ThreadFactory {
        @Override
        public Thread newThread(Runnable task) {
            var thread = new Thread(task, THREAD_NAME);
            thread.setDaemon(true);
            return thread;
        }
    }
}
