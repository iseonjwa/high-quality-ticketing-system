package com.iseonjwa.common.messaging.outbox;

import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KafkaOutboxEventSenderTest {
    @Test
    void 브로커_확인을_받으면_토픽_키_값을_그대로_보내고_Future가_완료된다() throws Exception {
        var producer = new MockProducer<>(true, null, new StringSerializer(), new StringSerializer());
        var sender = new KafkaOutboxEventSender(template(producer));

        var future = sender.send("payment.result", "100", "{\"a\":1}");

        future.get(1, TimeUnit.SECONDS);
        var record = producer.history().get(0);
        assertEquals("payment.result", record.topic());
        assertEquals("100", record.key());
        assertEquals("{\"a\":1}", record.value());
    }

    @Test
    void 브로커_확인_전에는_Future가_완료되지_않는다() {
        var producer = new MockProducer<>(false, null, new StringSerializer(), new StringSerializer());
        var sender = new KafkaOutboxEventSender(template(producer));

        var future = sender.send("payment.result", "100", "{}");

        assertFalse(future.isDone(), "전송만 하고 확인을 기다리지 않으면 실패를 모른 채 SENT가 된다");
    }

    @Test
    void 브로커가_실패를_응답하면_Future가_그_원인으로_실패한다() {
        var producer = new MockProducer<>(false, null, new StringSerializer(), new StringSerializer());
        var sender = new KafkaOutboxEventSender(template(producer));
        var future = sender.send("payment.result", "100", "{}");

        producer.errorNext(new RuntimeException("broker down"));

        var exception = assertThrows(ExecutionException.class, () -> future.get(1, TimeUnit.SECONDS));
        // Spring Kafka가 원인을 한 번 감싼다
        assertEquals("broker down", exception.getCause().getCause().getMessage());
    }

    @Test
    void 전송_호출_자체가_예외를_던져도_실패한_Future로_돌려준다() {
        ProducerFactory<String, String> factory = new ProducerFactory<>() {
            @Override
            public Producer<String, String> createProducer() {
                throw new IllegalStateException("메타데이터 시간 초과");
            }
        };
        var sender = new KafkaOutboxEventSender(new KafkaTemplate<>(factory));

        var future = sender.send("payment.result", "100", "{}");

        assertTrue(future.isCompletedExceptionally());
    }

    private static KafkaTemplate<String, String> template(MockProducer<String, String> producer) {
        ProducerFactory<String, String> factory = new ProducerFactory<>() {
            @Override
            public Producer<String, String> createProducer() {
                return producer;
            }
        };
        return new KafkaTemplate<>(factory);
    }
}
