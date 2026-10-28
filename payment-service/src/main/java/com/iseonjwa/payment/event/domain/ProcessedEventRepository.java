package com.iseonjwa.payment.event.domain;

import java.time.Instant;
import java.util.UUID;

public interface ProcessedEventRepository {
    /**
     * 처리한 이벤트로 기록한다. 이미 기록돼 있으면 false다. 호출한 트랜잭션에 참여하므로 업무 변경과 함께
     * 커밋되거나 함께 롤백된다(SA 10-4).
     */
    boolean markProcessed(UUID eventId, String consumerGroup, Instant processedAt);
}
