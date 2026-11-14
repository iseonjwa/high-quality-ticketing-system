package com.iseonjwa.reservation.queue.observability;

import com.iseonjwa.reservation.queue.domain.QueueStats;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

@Component
public class QueueMetrics {
    private final AtomicLong joined = new AtomicLong();
    private final AtomicLong admitted = new AtomicLong();
    private final AtomicLong waiting = new AtomicLong();
    private final AtomicLong admittedUnissued = new AtomicLong();
    private final AtomicLong activeTokens = new AtomicLong();

    public QueueMetrics(MeterRegistry meterRegistry) {
        // queue.joined가 필요하면 joined 객체의 get()을 불러서 값을 알아내라
        Gauge.builder("queue.joined", joined, AtomicLong::get)
                .description("판매 중인 회차에 지금까지 줄 선 인원").register(meterRegistry);
        Gauge.builder("queue.admitted", admitted, AtomicLong::get)
                .description("입장 처리된 번호").register(meterRegistry);
        Gauge.builder("queue.waiting", waiting, AtomicLong::get)
                .description("아직 차례가 오지 않은 대기 인원").register(meterRegistry);
        Gauge.builder("queue.admitted.unissued", admittedUnissued, AtomicLong::get)
                .description("차례가 왔는데 입장 토큰을 받지 않은 인원").register(meterRegistry);
        Gauge.builder("queue.entry.tokens.active", activeTokens, AtomicLong::get)
                .description("유효한 입장 토큰을 가진 인원").register(meterRegistry);
    }

    public void update(QueueStats queueStats) {
        joined.set(queueStats.joined());
        admitted.set(queueStats.admitted());
        waiting.set(queueStats.waiting());
        admittedUnissued.set(queueStats.admittedUnissued());
        activeTokens.set(queueStats.activeTokens());
    }
}
