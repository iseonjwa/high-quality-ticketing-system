package com.iseonjwa.reservation.seathold.presentation;

import com.iseonjwa.common.response.BusinessException;
import com.iseonjwa.common.response.CommonErrorCode;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * 선점 API가 동시에 처리하는 요청 수의 상한(ADR 0062 3번). 자리를 얻지 못한 요청은 DB 커넥션을 잡기 전에 503
 * {@code SERVICE_UNAVAILABLE}로 거절한다. 잠긴 좌석을 기다리는 선점이 커넥션 풀을 다 쥐어 좌석 조회처럼 상관없는
 * API까지 막히는 번짐과, 응답 시한(3초)이 지난 뒤 서버가 좌석을 잡는 유령 선점을 끊는다.
 *
 * <p>{@code seat-hold.max-concurrent}가 0이면 끈다(기본). 값은 커넥션 풀 크기보다 작게 두어 다른 API의 자리를 남긴다.
 * {@code seat-hold.max-concurrent-wait}만큼은 자리를 기다린다. 순간 몰림은 받아 주고, 그보다 오래 밀릴 요청만 거절한다.
 */
@Component
public class SeatHoldConcurrencyLimit {
    private final Semaphore permits;
    private final Duration wait;
    private final int maxWaiting;
    private final AtomicInteger waiting = new AtomicInteger();
    private final Counter rejectedQueueFull;
    private final Counter rejectedWaitTimeout;
    private final Timer waited;
    private final Timer held;

    /**
     * @param maxWaiting 자리를 기다릴 수 있는 요청 수. 차 있으면 기다리지 않고 바로 503이다
     */
    public SeatHoldConcurrencyLimit(@Value("${seat-hold.max-concurrent:0}") int maxConcurrent,
            @Value("${seat-hold.max-concurrent-wait:200ms}") Duration wait,
            @Value("${seat-hold.max-concurrent-queue:64}") int maxWaiting, MeterRegistry meterRegistry) {
        if (maxConcurrent < 0 || wait.isNegative() || maxWaiting < 0) {
            throw new IllegalArgumentException("선점 동시 처리 상한·대기·대기 인원은 0 이상이어야 합니다: "
                    + maxConcurrent + ", " + wait + ", " + maxWaiting);
        }
        this.permits = maxConcurrent == 0 ? null : new Semaphore(maxConcurrent, true);
        this.wait = wait;
        this.maxWaiting = maxWaiting;
        // 이유를 나눠 센다. 대기 인원 초과(queue_full)는 몰림이 처리 한계를 넘은 것이고, 대기 시간 초과(wait_timeout)는
        // 자리를 쥔 요청이 오래 걸린 것(잠금 장애 등)이다
        this.rejectedQueueFull = rejectedCounter(meterRegistry, "queue_full");
        this.rejectedWaitTimeout = rejectedCounter(meterRegistry, "wait_timeout");
        // Note: 상한 값을 정하려면 거절 수만으로는 모자란다. 사용 중인 자리가 상한에 늘 붙어 있는지, 자리를 얼마나
        // 기다렸는지를 함께 봐야 "상한이 작아서 거절"인지 "처리 가능량을 넘어서 거절"인지 가를 수 있다
        this.waited = Timer.builder("seathold.concurrency.wait")
                .description("선점 동시 처리 상한의 자리를 기다린 시간(얻었든 못 얻었든)")
                .serviceLevelObjectives(Duration.ofMillis(1), Duration.ofMillis(10), Duration.ofMillis(50),
                        Duration.ofMillis(100), Duration.ofMillis(200))
                .register(meterRegistry);
        // 자리 점유 시간. 처리 한계는 대략 자리 수 ÷ 이 시간이다(커넥션 사용 시간보다 길다)
        this.held = Timer.builder("seathold.concurrency.held")
                .description("선점 동시 처리 상한의 자리를 쥔 시간")
                .serviceLevelObjectives(Duration.ofMillis(1), Duration.ofMillis(5), Duration.ofMillis(10),
                        Duration.ofMillis(50), Duration.ofMillis(100))
                .register(meterRegistry);
        if (permits != null) {
            Gauge.builder("seathold.concurrency.in_use", permits, held -> maxConcurrent - held.availablePermits())
                    .description("선점 동시 처리 상한에서 지금 쓰고 있는 자리 수")
                    .register(meterRegistry);
        }
    }

    // Note: 대기를 짧게 둔다. 길면 그동안 톰캣 스레드를 쥐어 커넥션 대기(1초)를 앞으로 옮긴 것뿐이 된다.
    // 0으로 두면 순간 몰림까지 잘라, 상한 없이도 다 처리하던 초당 300건에서 16~19%를 거절했다. 200ms는 0~1.4%였다(ADR 0062)
    public <T> T run(Supplier<T> hold) {
        if (permits == null) {
            return hold.get();
        }
        if (!acquire()) {
            throw new BusinessException(CommonErrorCode.SERVICE_UNAVAILABLE);
        }
        var started = System.nanoTime();
        try {
            return hold.get();
        } finally {
            permits.release();
            held.record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
        }
    }

    // Note: 자리를 기다리는 동안 요청은 Tomcat 작업 스레드를 쥔다. 기다리는 인원을 묶지 않으면 처리 한계 근처에서
    // 거의 모든 요청이 기다려 스레드(기본 200)가 바닥나고, 스레드를 못 받은 요청은 시한 없는 Tomcat 작업 대기열에서
    // 3초 타임아웃을 맞는다. 좌석 조회도 같은 스레드를 써서 함께 실패했다(seathold-pre-deploy-measurements.md 11절).
    // 대기 인원이 차 있으면 기다리지 않고 바로 503으로 돌려보내 스레드 점유를 "자리 수 + 대기 인원"으로 묶는다
    private boolean acquire() {
        var started = System.nanoTime();
        try {
            // Note: 시한 없는 tryAcquire()는 공정 세마포어에서도 기다리는 요청을 건너뛰어 자리를 가로챈다(Semaphore 문서).
            // 시한 0을 주면 줄 순서를 지킨다
            if (permits.tryAcquire(0, TimeUnit.NANOSECONDS)) {
                return true;
            }
            // 확인과 대기 진입이 원자적이지 않아 순간적으로 maxWaiting을 조금 넘을 수 있다. 상한의 목적(스레드 점유 묶기)에는 무해하다
            if (waiting.incrementAndGet() > maxWaiting) {
                waiting.decrementAndGet();
                rejectedQueueFull.increment();
                return false;
            }
            try {
                if (permits.tryAcquire(wait.toNanos(), TimeUnit.NANOSECONDS)) {
                    return true;
                }
                rejectedWaitTimeout.increment();
                return false;
            } finally {
                waiting.decrementAndGet();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            rejectedWaitTimeout.increment();
            return false;
        } finally {
            waited.record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
        }
    }

    private static Counter rejectedCounter(MeterRegistry registry, String reason) {
        return Counter.builder("seathold.concurrency.rejected")
                .description("선점 동시 처리 상한을 넘어 503으로 거절한 요청 수(이유별)")
                .tag("reason", reason)
                .register(registry);
    }
}
