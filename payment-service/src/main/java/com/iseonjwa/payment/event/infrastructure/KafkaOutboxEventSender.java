package com.iseonjwa.payment.event.infrastructure;

import com.iseonjwa.payment.event.application.OutboxEventSender;
import com.iseonjwa.payment.event.application.OutboxSendException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Component
public class KafkaOutboxEventSender implements OutboxEventSender {
    // 프로듀서 delivery.timeout.ms(10초)보다 조금 길게 기다린다. 임대(30초)보다는 충분히 짧아야 한다
    static final Duration SEND_TIMEOUT = Duration.ofSeconds(12);

    private final KafkaTemplate<String, String> kafka;

    public KafkaOutboxEventSender(KafkaTemplate<String, String> kafka) {
        this.kafka = kafka;
    }

    // Note: 브로커 확인(acks=all)을 받을 때까지 기다린다. 기다리지 않으면 전송 실패를 모른 채 SENT로 바꾼다.
    @Override
    public void send(String topic, String key, String payload) {
        try {
            kafka.send(topic, key, payload).get(SEND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new OutboxSendException("전송 대기 중 인터럽트", exception);
        } catch (ExecutionException | TimeoutException exception) {
            throw new OutboxSendException("브로커 확인을 받지 못했다: " + exception.getMessage(), exception);
        }
    }
}
