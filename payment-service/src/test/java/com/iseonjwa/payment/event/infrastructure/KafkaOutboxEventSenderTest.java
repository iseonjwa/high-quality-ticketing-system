package com.iseonjwa.payment.event.infrastructure;

import com.iseonjwa.payment.event.application.OutboxSendException;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class KafkaOutboxEventSenderTest {
    @Test
    void 브로커_확인을_받으면_토픽_키_값을_그대로_보낸다() {
        var producer = new MockProducer<>(true, null, new StringSerializer(), new StringSerializer());
        var sender = new KafkaOutboxEventSender(template(producer));

        sender.send("payment.result", "100", "{\"a\":1}");

        var record = producer.history().get(0);
        assertEquals("payment.result", record.topic());
        assertEquals("100", record.key());
        assertEquals("{\"a\":1}", record.value());
    }

    @Test
    void 브로커가_실패를_응답하면_예외를_던져_SENT로_바뀌지_않게_한다() {
        var producer = new MockProducer<>(false, null, new StringSerializer(), new StringSerializer());
        var sender = new KafkaOutboxEventSender(template(producer));
        var failure = new Thread(() -> {
            // 전송 요청이 들어온 뒤에 브로커 오류를 흘려 보낸다
            while (producer.history().isEmpty()) Thread.onSpinWait();
            producer.errorNext(new RuntimeException("broker down"));
        });
        failure.start();

        assertThrows(OutboxSendException.class, () -> sender.send("payment.result", "100", "{}"));
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
