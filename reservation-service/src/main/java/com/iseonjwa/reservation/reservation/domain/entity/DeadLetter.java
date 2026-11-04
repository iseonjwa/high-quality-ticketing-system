package com.iseonjwa.reservation.reservation.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * DLQ로 온 {@code payment.result} 메시지 한 건(ISE-74). 원문과 원본 위치를 남기고, 다시 처리해 닫았는지(RESOLVED) 사람이
 * 봐야 하는지(OPEN)를 기록한다.
 */
@Getter
@Entity
@Table(name = "dead_letters")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DeadLetter {
    public static final String REPLAYED = "REPLAYED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "source_topic", length = 100)
    private String sourceTopic;

    @Column(name = "source_partition")
    private Integer sourcePartition;

    @Column(name = "source_offset")
    private Long sourceOffset;

    @Column(name = "message_key", length = 100)
    private String messageKey;

    @Column(name = "event_key", nullable = false, length = 200)
    private String eventKey;

    @Column(name = "event_id")
    private UUID eventId;

    @Column(name = "payload_raw", nullable = false)
    private String payloadRaw;

    @Column(length = 500)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private DeadLetterStatus status;

    @Column(length = 20)
    private String resolution;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    private DeadLetter(DeadLetterMessage message, String eventKey, UUID eventId, Instant now) {
        this.sourceTopic = message.getSourceTopic();
        this.sourcePartition = message.getSourcePartition();
        this.sourceOffset = message.getSourceOffset();
        this.messageKey = message.getMessageKey();
        this.eventKey = eventKey;
        this.eventId = eventId;
        this.payloadRaw = message.getPayloadRaw();
        this.createdAt = now;
    }

    // 사람이 확인해야 하는 건
    public static DeadLetter open(DeadLetterMessage message, String eventKey, UUID eventId, String reason,
            Instant now) {
        var deadLetter = new DeadLetter(message, eventKey, eventId, now);
        deadLetter.status = DeadLetterStatus.OPEN;
        deadLetter.reason = reason;
        return deadLetter;
    }

    // DLQ 처리기가 다시 처리해 성공한 건
    public static DeadLetter replayed(DeadLetterMessage message, String eventKey, UUID eventId, Instant now) {
        var deadLetter = new DeadLetter(message, eventKey, eventId, now);
        deadLetter.status = DeadLetterStatus.RESOLVED;
        deadLetter.resolution = REPLAYED;
        deadLetter.resolvedAt = now;
        return deadLetter;
    }
}
