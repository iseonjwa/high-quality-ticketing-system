package com.iseonjwa.common.event;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record EventEnvelope<T>(UUID eventId, String eventType, int version, Instant occurredAt,
                               String producer, String traceId, T payload) {}
