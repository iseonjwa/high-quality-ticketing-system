package com.iseonjwa.reservation.seathold.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;

/**
 * 접수형 소비자의 처리 결과와 접수→처리 지연(ISE-17). 라벨에는 ID를 넣지 않는다.
 * 잠금 시한 초과와 시한이 지나 도착한 메시지 수는 소비자 잠금 시한과 줄 설계를 다시 볼 근거다
 * ({@code docs/learning/kwonwoo/qna/2026-11-13-ise17-consumer-race-and-lock-timeout-tradeoffs.md}).
 */
@Component
public class SeatHoldConsumerMetrics {
    static final String RESULT = "seathold.consumer.result";
    static final String DELAY = "seathold.consumer.delay";

    public enum Outcome {
        HELD("held"),
        SEAT_CONFLICT("seat_conflict"),
        // 좌석·매수 행 잠금을 시한 안에 못 얻어 SEAT_ALREADY_HELD로 닫음
        LOCK_TIMEOUT("lock_timeout"),
        LIMIT_EXCEEDED("limit_exceeded"),
        // 처리 시작 때 이미 30초가 지나 TIMEOUT으로 닫음
        LATE("late"),
        // 회차·좌석이 사라지는 등 업무로 처리할 수 없어 PROCESSING_FAILED로 닫음
        FAILED("failed"),
        // 이미 닫힌 요청(시한 정리, 앞선 전달)이라 건너뜀
        SKIPPED("skipped");

        private final String label;

        Outcome(String label) {
            this.label = label;
        }
    }

    private final Map<Outcome, Counter> results = new EnumMap<>(Outcome.class);
    private final Timer delay;

    public SeatHoldConsumerMetrics(MeterRegistry registry) {
        for (var outcome : Outcome.values()) {
            results.put(outcome, Counter.builder(RESULT)
                    .description("접수형 소비자 처리 결과 수")
                    .tag("outcome", outcome.label)
                    .register(registry));
        }
        // 시한(30초) 근처를 나눠 보도록 고정 버킷을 둔다. 시계열은 버킷 수만큼이다
        this.delay = Timer.builder(DELAY)
                .description("접수부터 소비자가 처리를 시작하기까지 걸린 시간")
                .serviceLevelObjectives(Duration.ofMillis(100), Duration.ofMillis(500), Duration.ofSeconds(1),
                        Duration.ofSeconds(3), Duration.ofSeconds(10), Duration.ofSeconds(20), Duration.ofSeconds(30))
                .register(registry);
    }

    public void record(Outcome outcome) {
        results.get(outcome).increment();
    }

    public void recordDelay(Duration elapsed) {
        delay.record(elapsed.isNegative() ? Duration.ZERO : elapsed);
    }
}
