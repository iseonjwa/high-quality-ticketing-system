package com.iseonjwa.payment.infrastructure.job;

import com.iseonjwa.payment.observability.PaymentMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import java.time.Clock;
import java.util.List;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 배경 작업을 주기적으로 돌리는 전용 실행기다(events-and-reliability.md 6·7절). 결제는 이 클래스로 실행기를 둘 만든다:
 * {@code recovery}(결제 재확인, 환불 워커, 스레드 2개)와 {@code housekeeping}(보관 정리, 스레드 1개). 작업마다 한 주기에
 * 최대 {@code maxPerTick}건을 한 건씩 처리하고, 처리할 건이 없거나 PG 복구 허가를 못 얻으면 그 주기를 끝낸다.
 *
 * <p>Note: Spring {@code TaskScheduler} 빈을 만들지 않고 이 컴포넌트가 실행기를 직접 소유한다. 그런 빈을 하나 만들면 Boot의
 * 기본 스케줄러가 빠지고, 기본 스케줄러를 쓰는 다른 {@code @Scheduled} 작업이 그 빈으로 옮겨 가 섞인다. 실행기를 나누는 이유는
 * 느린 정리 배치가 PG 재확인이나 환불을 늦추지 않게 하려는 것이다(스레드 이름 {@code <접두사>-N}으로 구분한다).
 *
 * <p>Note: 작업별 지표는 {@code runTick}이 남긴다. 주기가 예외 없이 끝나면 마지막 성공 시각을 갱신하고(할 일이 없어 0건을 처리한
 * 주기도 성공이다), {@code Throwable}로 끝나면 실패 수를 올린다. 마지막 성공 시각은 기동 때 등록 시각으로 시작한다. 그래야 첫
 * 주기가 오기 전에 작업 정체 알림이 거짓으로 울리지 않고, 한 번도 성공하지 못한 작업은 알림 기준 시간이 지나면 정체로 보인다.
 *
 * <p>Note: {@code scheduleWithFixedDelay}는 작업이 예외로 끝나면 이후 실행을 모두 멈춘다. 한 주기의 예외는 여기서 잡아 ERROR로
 * 남기고 다음 주기를 계속한다. 잡지 못한 건은 임대(30초)가 끝나면 다시 대상이 된다.
 */
public class BackgroundJobScheduler implements SmartLifecycle {
    // 진행 중인 한 건의 최악 시간: claim 트랜잭션 + PG 조회 2.5초 + 늦은 승인 방지 2.5초 + 전이 트랜잭션, 약 7초. 그 두 배 가까이 기다린다
    private static final long STOP_WAIT_SECONDS = 15;

    private static final Logger log = LoggerFactory.getLogger(BackgroundJobScheduler.class);

    private final String threadNamePrefix;
    private final int threads;
    private final List<BackgroundJob> jobs;
    private final PaymentMetrics metrics;
    private final Clock clock;
    private ScheduledThreadPoolExecutor executor;
    private volatile boolean stopping;

    /**
     * @param threadNamePrefix 스레드 이름 접두사. 스레드는 {@code <접두사>-1}, {@code <접두사>-2}...로 이름이 붙는다
     * @param threads          실행기의 스레드 수
     * @param jobs             이 실행기에서 돌릴 작업. 비어 있으면 실행기를 만들지 않는다
     * @param metrics          작업별 마지막 성공 시각과 실패 수를 남기는 곳
     * @param clock            마지막 성공 시각을 정하는 시계
     */
    public BackgroundJobScheduler(String threadNamePrefix, int threads, List<BackgroundJob> jobs,
            PaymentMetrics metrics, Clock clock) {
        if (threads < 1) throw new IllegalArgumentException("스레드 수는 1 이상이어야 합니다 prefix=" + threadNamePrefix);
        this.threadNamePrefix = threadNamePrefix;
        this.threads = threads;
        this.jobs = List.copyOf(jobs);
        this.metrics = metrics;
        this.clock = clock;
        this.jobs.forEach(job -> metrics.trackJob(job.name(), clock.instant()));
    }

    /** 이 실행기에 등록된 작업 이름. 어떤 작업이 어느 실행기에 올랐는지 확인할 때 쓴다. */
    public List<String> jobNames() {
        return jobs.stream().map(BackgroundJob::name).toList();
    }

    @Override
    public synchronized void start() {
        // 등록된 작업이 없으면 실행기도 스레드도 만들지 않는다
        if (executor != null || jobs.isEmpty()) return;
        stopping = false;
        executor = new ScheduledThreadPoolExecutor(threads, new NamedThreadFactory(threadNamePrefix));
        executor.setRemoveOnCancelPolicy(true);
        for (var job : jobs) {
            executor.scheduleWithFixedDelay(() -> runTick(job), job.initialDelay().toMillis(),
                    job.interval().toMillis(), TimeUnit.MILLISECONDS);
        }
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

    void runTick(BackgroundJob job) {
        try {
            tick(job);
            metrics.jobSucceeded(job.name(), clock.instant());
        } catch (Throwable throwable) {
            // Note: ScheduledThreadPoolExecutor는 주기 작업이 예외로 끝나면 다음 실행을 예약하지 않고, 예외는 아무도 읽지 않는
            // Future에만 남는다. Error까지 여기서 잡아 로그를 남기고 다음 주기를 계속한다(Spring @Scheduled의 LOG_AND_SUPPRESS와 같다)
            metrics.jobFailed(job.name());
            log.error("배경 작업 주기 실패, 다음 주기에 계속한다 scheduler={} job={}", threadNamePrefix, job.name(), throwable);
        }
    }

    /** 한 주기. 처리한 건수를 돌려준다(테스트에서도 부른다). */
    public int tick(BackgroundJob job) {
        var handled = 0;
        while (handled < job.maxPerTick() && !stopping && !Thread.currentThread().isInterrupted()) {
            if (!job.next().getAsBoolean()) break;
            handled++;
        }
        return handled;
    }

    private static final class NamedThreadFactory implements ThreadFactory {
        private final String prefix;
        private final AtomicInteger sequence = new AtomicInteger();

        NamedThreadFactory(String prefix) {
            this.prefix = prefix;
        }

        @Override
        public Thread newThread(Runnable task) {
            var thread = new Thread(task, prefix + "-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }
}
