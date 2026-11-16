package com.iseonjwa.reservation.seathold.infrastructure.messaging;

import com.iseonjwa.common.messaging.outbox.OutboxEventSender;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

// 직접 발행(ISE-18)이 Outbox와 같은 봉투로 보내고, 실패해도 예외 없이 지표만 남기는지 본다
class KafkaSeatHoldDirectPublisherTest {
    private static final Instant NOW = Instant.parse("2026-11-16T03:00:00.123456Z");

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final List<String[]> sends = new ArrayList<>();

    @Test
    void 브로커_확인을_받으면_보낸_수를_센다() {
        var publisher = publisher(payload -> CompletableFuture.completedFuture(null), Duration.ofSeconds(1));

        publisher.publish("seat-hold.request", "70:A", "SeatHoldRequested", 1, Map.of("requestId", "r-1"));

        assertEquals(1.0, count("sent"));
        assertEquals(0.0, count("failed"));
        var sent = sends.getFirst();
        assertEquals("seat-hold.request", sent[0]);
        assertEquals("70:A", sent[1]);
        var envelope = mapper.readTree(sent[2]);
        assertEquals("SeatHoldRequested", envelope.get("eventType").asString());
        assertEquals(1, envelope.get("version").asInt());
        assertEquals("reservation-service", envelope.get("producer").asString());
        assertEquals("r-1", envelope.get("payload").get("requestId").asString());
    }

    @Test
    void 전송이_실패해도_예외_없이_실패_수를_센다() {
        var publisher = publisher(payload -> CompletableFuture.failedFuture(new IllegalStateException("down")),
                Duration.ofSeconds(1));

        assertDoesNotThrow(() -> publisher.publish("seat-hold.request", "70:A", "SeatHoldRequested", 1, Map.of()));

        assertEquals(0.0, count("sent"));
        assertEquals(1.0, count("failed"));
    }

    @Test
    void 확인_시한을_넘기면_실패로_센다() {
        var publisher = publisher(payload -> new CompletableFuture<>(), Duration.ofMillis(20));

        assertDoesNotThrow(() -> publisher.publish("seat-hold.request", "70:A", "SeatHoldRequested", 1, Map.of()));

        assertEquals(1.0, count("failed"));
        assertEquals(1L, registry.get("seathold.direct_publish.ack").timer().count());
    }

    private KafkaSeatHoldDirectPublisher publisher(java.util.function.Function<String, CompletableFuture<Void>> result,
            Duration timeout) {
        OutboxEventSender sender = (topic, key, payload) -> {
            sends.add(new String[] {topic, key, payload});
            return result.apply(payload);
        };
        return new KafkaSeatHoldDirectPublisher(sender, mapper, Clock.fixed(NOW, ZoneOffset.UTC),
                "reservation-service", timeout, registry);
    }

    private double count(String result) {
        return registry.get("seathold.direct_publish").tag("result", result).counter().count();
    }
}
