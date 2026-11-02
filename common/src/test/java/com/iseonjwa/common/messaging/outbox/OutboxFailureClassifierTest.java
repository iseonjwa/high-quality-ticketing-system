package com.iseonjwa.common.messaging.outbox;

import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.errors.InvalidTopicException;
import org.apache.kafka.common.errors.NetworkException;
import org.apache.kafka.common.errors.RecordBatchTooLargeException;
import org.apache.kafka.common.errors.RecordTooLargeException;
import org.apache.kafka.common.errors.SerializationException;
import org.apache.kafka.common.errors.TimeoutException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OutboxFailureClassifierTest {
    @Test
    void 직렬화_실패_크기_초과_잘못된_토픽은_영구_오류다() {
        assertTrue(OutboxFailureClassifier.isPermanent(new SerializationException("직렬화")));
        assertTrue(OutboxFailureClassifier.isPermanent(new RecordTooLargeException("큼")));
        assertTrue(OutboxFailureClassifier.isPermanent(new RecordBatchTooLargeException("배치가 큼")));
        assertTrue(OutboxFailureClassifier.isPermanent(new InvalidTopicException("토픽")));
    }

    @Test
    void Spring_Kafka가_감싼_원인도_끝까지_벗겨_영구_오류로_본다() {
        var wrapped = new KafkaException("Failed to send", new RuntimeException("중간", new RecordTooLargeException("큼")));

        assertTrue(OutboxFailureClassifier.isPermanent(wrapped));
    }

    @Test
    void 시한_초과와_브로커_불가는_일시_오류다() {
        assertFalse(OutboxFailureClassifier.isPermanent(new TimeoutException("시한")));
        assertFalse(OutboxFailureClassifier.isPermanent(new NetworkException("브로커 불가")));
        assertFalse(OutboxFailureClassifier.isPermanent(new KafkaException("Failed to send", new TimeoutException("시한"))));
        assertFalse(OutboxFailureClassifier.isPermanent(new IllegalStateException("브로커 확인 시한을 넘겼다")));
    }

    @Test
    void 알_수_없는_예외는_일시_오류다() {
        assertFalse(OutboxFailureClassifier.isPermanent(new RuntimeException("알 수 없음")));
        assertFalse(OutboxFailureClassifier.isPermanent(new KafkaException("일반 Kafka 오류")));
        assertFalse(OutboxFailureClassifier.isPermanent(new NullPointerException()));
    }

    @Test
    void 원인이_순환해도_끝난다() {
        var cyclic = new RuntimeException("순환") {
            @Override
            public synchronized Throwable getCause() {
                return this;
            }
        };

        assertFalse(OutboxFailureClassifier.isPermanent(cyclic));
    }
}
