package com.iseonjwa.reservation.reservation.domain.entity;

import lombok.Getter;

/**
 * DLQ 레코드에서 꺼낸 원본 메시지의 위치와 원문. Kafka 타입을 application에 넘기지 않으려고 값만 담는다.
 *
 * <p>Note: 저장이 내용 때문에 실패하면 DLQ 처리기가 같은 레코드를 끝없이 다시 처리해 파티션이 멈춘다. 그래서 만들 때
 * PostgreSQL 문자열이 담지 못하는 NUL 문자를 U+FFFD로 바꾸고, 컬럼 길이(토픽·키 100자)에 맞춰 자른다. 결제 서비스
 * DeadLetterService와 같은 규칙이다.
 */
@Getter
public class DeadLetterMessage {
    public static final int KEY_LENGTH = 100;

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
        return new DeadLetterMessage(truncate(clean(sourceTopic), KEY_LENGTH), sourcePartition, sourceOffset,
                truncate(clean(messageKey), KEY_LENGTH), payloadRaw == null ? "" : clean(payloadRaw));
    }

    // 봉투를 읽지 못했을 때의 멱등 키. 같은 원본이 DLQ에 두 번 들어와도 원본 위치는 같아 한 건으로 묶인다
    public String positionKey() {
        return sourceTopic + "-" + sourcePartition + "-" + sourceOffset;
    }

    // PostgreSQL 텍스트는 NUL 문자를 저장하지 못한다
    public static String clean(String value) {
        return value == null ? null : value.replace('\u0000', '�');
    }

    public static String truncate(String value, int length) {
        return value == null || value.length() <= length ? value : value.substring(0, length);
    }
}
