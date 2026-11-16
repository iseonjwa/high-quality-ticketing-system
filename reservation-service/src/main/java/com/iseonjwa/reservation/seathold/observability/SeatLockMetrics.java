package com.iseonjwa.reservation.seathold.observability;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * 좌석 조건부 UPDATE 한 문장이 걸린 시간(잠금 대기 포함)을 잰다. 동기형과 접수형 소비자가 함께 쓴다.
 *
 * <p>Note: 잠금을 기다리지 않으면 이 문장은 1ms 안쪽에 끝나고, 기다리면 {@code lock_timeout}(50ms, ADR 0061)까지 늘어난다.
 * 그래서 이 분포가 곧 좌석 잠금 대기 분포다. 선점이 느릴 때 커넥션 대기(Hikari 지표)와 잠금 대기를 가르려고 둔다.
 * 시계열을 늘리지 않게 라벨 없이 잠금 시한 근처 버킷만 둔다.
 */
@Component
public class SeatLockMetrics {
    static final String NAME = "seathold.seat_lock";

    private final Timer timer;

    public SeatLockMetrics(MeterRegistry registry) {
        this.timer = Timer.builder(NAME)
                .description("좌석 조건부 UPDATE 소요 시간(잠금 대기 포함)")
                .serviceLevelObjectives(Duration.ofMillis(1), Duration.ofMillis(5), Duration.ofMillis(10),
                        Duration.ofMillis(25), Duration.ofMillis(50), Duration.ofMillis(100))
                .register(registry);
    }

    /** 잠금 시한 초과로 예외가 나도 걸린 시간을 남긴다. */
    public <T> T record(Supplier<T> seatUpdate) {
        return timer.record(seatUpdate);
    }
}
