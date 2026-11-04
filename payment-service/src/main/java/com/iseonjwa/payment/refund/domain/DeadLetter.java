package com.iseonjwa.payment.refund.domain;

import java.time.Instant;
import java.util.UUID;

/** {@code dead_letters} 한 행(data-model.md 8절). 값 조회용이다. */
public record DeadLetter(Long id, String sourceTopic, Integer sourcePartition, Long sourceOffset, String messageKey,
                         String eventKey, UUID eventId, String eventType, String payloadRaw, String reason,
                         DeadLetterStatus status, DeadLetterResolution resolution, Long refundId, Instant createdAt,
                         Instant resolvedAt) {}
