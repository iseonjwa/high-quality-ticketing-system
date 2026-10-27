package com.iseonjwa.common.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Kafka 이벤트 공통 봉투 (SA 10-2).
 * 소비자는 {@code eventId}로 중복 처리를 막고(processed_event), {@code traceId}로 요청과 이벤트를 잇는다.
 * 이벤트마다 다른 내용은 {@code payload}에 넣는다 (SA 10-3).
 */
public record EventEnvelope<T>(
        UUID eventId,
        String eventType,
        int version,
        Instant occurredAt,
        String producer,
        String traceId,
        T payload) {

    public static <T> EventEnvelope<T> of(String eventType, int version, String producer, String traceId, T payload) {
        return new EventEnvelope<>(UUID.randomUUID(), eventType, version, Instant.now(), producer, traceId, payload);
    }
}
