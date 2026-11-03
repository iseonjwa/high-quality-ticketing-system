package com.iseonjwa.reservation.reservation.domain.entity;

import lombok.Getter;

/**
 * DLQ 레코드에서 꺼낸 원본 메시지의 위치와 원문. Kafka 타입을 application에 넘기지 않으려고 값만 담는다.
 */
@Getter
public class DeadLetterMessage {
    private final String sourceTopic;
    private final Integer sourcePartition;
    private final Long sourceOffset;
    private final String messageKey;
    private final String payloadRaw;

    private DeadLetterMessage(String sourceTopic, Integer sourcePartition, Long sourceOffset, String messageKey,
            String payloadRaw) {
        this.sourceTopic = sourceTopic;
        this.sourcePartition = sourcePartition;
        this.sourceOffset = sourceOffset;
        this.messageKey = messageKey;
        this.payloadRaw = payloadRaw;
    }

    public static DeadLetterMessage of(String sourceTopic, Integer sourcePartition, Long sourceOffset,
            String messageKey, String payloadRaw) {
        return new DeadLetterMessage(sourceTopic, sourcePartition, sourceOffset, messageKey,
                payloadRaw == null ? "" : payloadRaw);
    }

    // 봉투를 읽지 못했을 때의 멱등 키. 같은 원본이 DLQ에 두 번 들어와도 원본 위치는 같아 한 건으로 묶인다
    public String positionKey() {
        return sourceTopic + "-" + sourcePartition + "-" + sourceOffset;
    }
}
