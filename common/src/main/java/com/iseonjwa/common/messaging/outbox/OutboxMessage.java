package com.iseonjwa.common.messaging.outbox;

import java.util.UUID;

/**
 * 릴레이가 집은 발행 대기 행. 페이로드는 {@code EventEnvelope} 전체를 직렬화한 JSON이다.
 */
public record OutboxMessage(long id, UUID eventId, String topic, String messageKey, String eventType, String payload) {}
