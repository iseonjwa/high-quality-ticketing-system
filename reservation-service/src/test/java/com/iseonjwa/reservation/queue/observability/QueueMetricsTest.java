package com.iseonjwa.reservation.queue.observability;

import com.iseonjwa.reservation.queue.domain.QueueStats;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

// 대기열 게이지가 마지막으로 넣은 값을 내보내는지 확인한다 (ISE-61)
class QueueMetricsTest {

    final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    final QueueMetrics metrics = new QueueMetrics(registry);

    @Test
    void 처음에는_모두_0이다() {
        assertEquals(0.0, gauge("queue.joined"));
        assertEquals(0.0, gauge("queue.admitted"));
        assertEquals(0.0, gauge("queue.waiting"));
        assertEquals(0.0, gauge("queue.admitted.unissued"));
        assertEquals(0.0, gauge("queue.entry.tokens.active"));
    }

    @Test
    void 넣은_값을_게이지로_내보낸다() {
        metrics.update(new QueueStats(553, 500, 53, 12, 40));

        assertEquals(553.0, gauge("queue.joined"));
        assertEquals(500.0, gauge("queue.admitted"));
        assertEquals(53.0, gauge("queue.waiting"));
        assertEquals(12.0, gauge("queue.admitted.unissued"));
        assertEquals(40.0, gauge("queue.entry.tokens.active"));
    }

    @Test
    void 다시_넣으면_더하지_않고_바꾼다() {
        metrics.update(new QueueStats(553, 500, 53, 12, 40));
        metrics.update(new QueueStats(600, 550, 50, 10, 30));

        assertEquals(600.0, gauge("queue.joined"));
        assertEquals(50.0, gauge("queue.waiting"));
    }

    private double gauge(String name) {
        return registry.get(name).gauge().value();
    }
}
