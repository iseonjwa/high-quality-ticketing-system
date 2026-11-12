package com.iseonjwa.payment.warmup;

import com.iseonjwa.payment.observability.PaymentMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.SmartLifecycle;

import java.util.List;
import java.util.function.LongSupplier;

/**
 * 트래픽을 받기 전에 승인 경로의 읽기 전용 조각을 미리 실행한다(ISE-243). 웹 서버가 연결을 받기 전에 {@code start()}에서 동기로 끝낸다.
 * Eureka 등록은 웹 서버가 뜬 뒤이므로 그보다도 앞선다. 단계마다 회차별 시간을 재서 첫 회와 그 뒤 회차의 차이를 한 줄 로그로 남긴다.
 *
 * <p>Note: 웜업은 최적화이지 기동 조건이 아니다. 단계의 예외는 WARN으로 남기고 다음 단계로 간다.
 *
 * <p>Note: phase는 웹 서버 시작 단계 바로 앞이다. DB·JPA·PG 어댑터 같은 일반 빈은 생명주기 단계 전에 모두 만들어져 있다.
 */
public class PaymentWarmup implements SmartLifecycle {
    /** 웹 서버 시작({@code WebServerStartStopLifecycle})보다 앞선다. */
    public static final int PHASE = WebServerApplicationContext.START_STOP_LIFECYCLE_PHASE - 1;

    private static final Logger log = LoggerFactory.getLogger(PaymentWarmup.class);
    private static final double NANOS_PER_MILLI = 1_000_000d;
    private static final double NANOS_PER_SECOND = 1_000_000_000d;

    private final List<WarmupStep> steps;
    private final WarmupProperties properties;
    private final PaymentMetrics metrics;
    private final LongSupplier nanoTime;
    private volatile boolean running;

    public PaymentWarmup(List<WarmupStep> steps, WarmupProperties properties, PaymentMetrics metrics) {
        this(steps, properties, metrics, System::nanoTime);
    }

    PaymentWarmup(List<WarmupStep> steps, WarmupProperties properties, PaymentMetrics metrics, LongSupplier nanoTime) {
        this.steps = List.copyOf(steps);
        this.properties = properties;
        this.metrics = metrics;
        this.nanoTime = nanoTime;
    }

    @Override
    public void start() {
        if (running) return;
        var startedAt = nanoTime.getAsLong();
        var deadline = startedAt + properties.maxDuration().toNanos();
        var aborted = false;
        for (var step : steps) {
            if (runStep(step, deadline)) aborted = true;
        }
        var totalNanos = nanoTime.getAsLong() - startedAt;
        metrics.warmupTotal(totalNanos / NANOS_PER_SECOND);
        log.info("event=PAYMENT_WARMUP step=total totalMs={} aborted={}", millis(totalNanos), aborted);
        running = true;
    }

    // 시간 상한을 넘어 중단했으면 true
    private boolean runStep(WarmupStep step, long deadline) {
        long first = -1;
        long second = -1;
        long restSum = 0;
        var done = 0;
        var aborted = false;
        try {
            for (var i = 0; i < properties.iterations(); i++) {
                if (nanoTime.getAsLong() - deadline >= 0) {
                    aborted = true;
                    break;
                }
                var begin = nanoTime.getAsLong();
                step.action().run();
                var elapsed = nanoTime.getAsLong() - begin;
                if (i == 0) first = elapsed;
                else if (i == 1) second = elapsed;
                else restSum += elapsed;
                done++;
            }
        } catch (Exception exception) {
            log.warn("event=PAYMENT_WARMUP_STEP_FAILED step={} completed={} cause={}", step.name(), done,
                    exception.toString());
        }
        if (first >= 0) {
            metrics.warmupFirstCall(step.name(), first / NANOS_PER_SECOND);
            var restAvg = done > 2 ? restSum / (done - 2) : 0;
            log.info("event=PAYMENT_WARMUP step={} firstMs={} secondMs={} restAvgMs={} iterations={}{}", step.name(),
                    millis(first), second >= 0 ? millis(second) : "-", millis(restAvg), done,
                    aborted ? " aborted=max-duration" : "");
        } else if (aborted) {
            log.info("event=PAYMENT_WARMUP step={} skipped=max-duration", step.name());
        }
        return aborted;
    }

    private static String millis(long nanos) {
        var millis = nanos / NANOS_PER_MILLI;
        return millis >= 10 ? Long.toString(Math.round(millis)) : String.format("%.1f", millis);
    }

    @Override
    public void stop() {
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return PHASE;
    }
}
