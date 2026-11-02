package com.iseonjwa.common.messaging.outbox;

import org.apache.kafka.common.errors.InvalidTopicException;
import org.apache.kafka.common.errors.RecordBatchTooLargeException;
import org.apache.kafka.common.errors.RecordTooLargeException;
import org.apache.kafka.common.errors.SerializationException;

import java.util.Set;

/**
 * 발행 실패가 다시 시도해도 소용없는 영구 오류인지 가른다(ADR 0022 결정 2).
 *
 * <p>Note: 목록에 있는 예외만 영구 오류다. 시한 초과, 브로커 불가, 알 수 없는 예외는 모두 일시 오류로 보고 계속
 * 재시도한다. 모르는 예외를 FAILED로 보내면 정상 이벤트가 멈추고 같은 키의 뒤 이벤트까지 막히기 때문이다.
 * Spring Kafka는 원인을 {@code KafkaException}으로 감싸므로 원인 사슬 전체를 훑는다.
 */
final class OutboxFailureClassifier {
    private static final Set<Class<? extends Throwable>> PERMANENT = Set.of(SerializationException.class,
            RecordTooLargeException.class, RecordBatchTooLargeException.class, InvalidTopicException.class);

    private OutboxFailureClassifier() {}

    static boolean isPermanent(Throwable failure) {
        var visited = 0;
        // 원인 사슬이 순환해도 끝나도록 깊이를 제한한다
        for (var current = failure; current != null && visited < 32; current = current.getCause(), visited++) {
            for (var type : PERMANENT) {
                if (type.isInstance(current)) return true;
            }
        }
        return false;
    }
}
