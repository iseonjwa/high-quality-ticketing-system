package com.iseonjwa.reservation.lottery.observability;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LotteryMetricsTest {
    private static final Instant NOW = Instant.parse("2026-11-05T00:10:00Z");

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final LotteryMetrics metrics = new LotteryMetrics(registry, Clock.fixed(NOW, ZoneOffset.UTC));

    // 변이 테스트 대상: 미리 등록하지 않으면 첫 발생을 increase()가 놓쳐 경보가 울리지 않는다
    @Test
    void 발생_전에도_라벨별_시계열을_0으로_내보낸다() {
        for (String job : List.of("assignment", "succession", "finish")) {
            assertEquals(0, registry.get("lottery.job.failures").tag("task", job).counter().count());
        }
        for (String reason : List.of("BILLING_KEY_MISSING", "CARD_DECLINED", "HOLD_EXPIRED", "NO_SEAT", "OTHER")) {
            assertEquals(0, registry.get("lottery.entry.failures").tag("reason", reason).counter().count());
        }
        for (String result : List.of("assigned", "error", "rejected")) {
            assertEquals(0, registry.get("lottery.assignment.results").tag("result", result).counter().count());
        }
        for (String result : List.of("paid", "failed")) {
            assertEquals(0, registry.get("lottery.settlement.duration").tag("result", result).timer().count());
        }
    }

    @Test
    void 결제_완료_시간은_추첨_시각부터_잰다() {
        metrics.paid(NOW.minus(Duration.ofMinutes(7)));

        var timer = registry.get("lottery.settlement.duration").tag("result", "paid").timer();
        assertEquals(1, timer.count());
        assertEquals(420, timer.totalTime(TimeUnit.SECONDS));
    }

    @Test
    void 진행_상태를_게이지에_반영한다() {
        metrics.updateProgress(new LotteryMetrics.Progress(2, 120, 30, 400));

        assertEquals(2, registry.get("lottery.unfinished.count").gauge().value());
        assertEquals(120, registry.get("lottery.unfinished.oldest.seconds").gauge().value());
        assertEquals(30, registry.get("lottery.winners.unsettled").gauge().value());
        assertEquals(400, registry.get("lottery.seats.remaining").gauge().value());
    }
}
