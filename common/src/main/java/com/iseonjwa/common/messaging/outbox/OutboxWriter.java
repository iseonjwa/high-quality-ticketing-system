package com.iseonjwa.common.messaging.outbox;

import com.iseonjwa.common.event.EventEnvelope;
import org.slf4j.MDC;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * 업무 변경과 같은 트랜잭션에서 이벤트를 Outbox에 쓴다. Kafka를 직접 부르지 않는다.
 *
 * <p>Note: 활성 트랜잭션이 없으면 예외를 던진다. 별도 트랜잭션이면 업무는 커밋됐는데 이벤트가 없거나
 * 이벤트만 남는다. 호출은 상태 변경이 실제로 일어났을 때(조건부 UPDATE의 영향 행이 1일 때)만 한다.
 */
public class OutboxWriter {
    private final OutboxRepository repository;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final String producer;

    public OutboxWriter(OutboxRepository repository, ObjectMapper mapper, Clock clock, String producer) {
        this.repository = repository;
        this.mapper = mapper;
        this.clock = clock;
        this.producer = producer;
    }

    /** 봉투에 담아 Outbox에 쓰고 이벤트 ID를 돌려준다. 파티션 키는 같은 키끼리 순서가 유지된다. {@code version}은 이벤트 타입별 스키마 버전이다. */
    public UUID write(String topic, String messageKey, String eventType, int version, Object payload) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalTransactionStateException("Outbox 쓰기는 업무 변경과 같은 트랜잭션 안에서 해야 합니다");
        }
        // SA 10-2의 예시처럼 밀리초 정밀도로 내보낸다
        var now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        var envelope = new EventEnvelope<>(UUID.randomUUID(), eventType, version, now, producer,
                MDC.get("traceId"), payload);
        repository.insert(envelope.eventId(), topic, messageKey, eventType, mapper.writeValueAsString(envelope), now);
        return envelope.eventId();
    }
}
