package com.iseonjwa.payment.event.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * 업무 변경과 같은 트랜잭션에 저장하는 발행 대기 이벤트. 기록용 테이블이라 BaseEntity를 상속하지 않는다.
 *
 * <p>Note: 발행 상태 컬럼은 {@code updatable = false}다. 릴레이가 저장소의 조건부 UPDATE로만 바꿔
 * 여러 릴레이 실행이 같은 행을 덮어쓰지 않게 한다.
 */
@Entity
@Table(name = "outbox")
public class OutboxEvent {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", nullable = false, updatable = false)
    private UUID eventId;

    @Column(nullable = false, updatable = false, length = 100)
    private String topic;

    @Column(name = "message_key", nullable = false, updatable = false, length = 100)
    private String messageKey;

    @Column(name = "event_type", nullable = false, updatable = false, length = 100)
    private String eventType;

    // EventEnvelope 전체를 직렬화한 JSON이다
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, updatable = false)
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 10)
    private OutboxStatus status;

    @JdbcTypeCode(SqlTypes.SMALLINT)
    @Column(nullable = false, updatable = false)
    private int attempts;

    @Column(name = "next_attempt_at", nullable = false, updatable = false)
    private Instant nextAttemptAt;

    @Column(name = "claimed_until", updatable = false)
    private Instant claimedUntil;

    @Column(name = "last_error", updatable = false, length = 500)
    private String lastError;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "sent_at", updatable = false)
    private Instant sentAt;

    protected OutboxEvent() {}

    private OutboxEvent(UUID eventId, String topic, String messageKey, String eventType, String payload, Instant now) {
        this.eventId = eventId;
        this.topic = topic;
        this.messageKey = messageKey;
        this.eventType = eventType;
        this.payload = payload;
        this.status = OutboxStatus.PENDING;
        this.nextAttemptAt = now;
        this.createdAt = now;
    }

    public static OutboxEvent pending(UUID eventId, String topic, String messageKey, String eventType, String payload,
            Instant now) {
        return new OutboxEvent(eventId, topic, messageKey, eventType, payload, now);
    }

    public Long getId() { return id; }
    public UUID getEventId() { return eventId; }
    public String getTopic() { return topic; }
    public String getMessageKey() { return messageKey; }
    public String getEventType() { return eventType; }
    public String getPayload() { return payload; }
    public OutboxStatus getStatus() { return status; }
    public int getAttempts() { return attempts; }
    public Instant getNextAttemptAt() { return nextAttemptAt; }
    public Instant getClaimedUntil() { return claimedUntil; }
    public String getLastError() { return lastError; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getSentAt() { return sentAt; }
}
