package com.iseonjwa.common.messaging.outbox;

import org.springframework.kafka.core.KafkaTemplate;

import java.util.concurrent.CompletableFuture;

public class KafkaOutboxEventSender implements OutboxEventSender {
    private final KafkaTemplate<String, String> kafka;

    public KafkaOutboxEventSender(KafkaTemplate<String, String> kafka) {
        this.kafka = kafka;
    }

    // Note: 브로커 확인(acks=all)을 받아야 완료되는 Future를 돌려준다. 보내기만 하고 SENT로 바꾸면 전송 실패를 모른다.
    // send 호출 자체가 메타데이터를 기다리며 막힐 수 있으므로(max.block.ms) 릴레이가 마감 시각을 확인한다.
    @Override
    public CompletableFuture<Void> send(String topic, String key, String payload) {
        try {
            return kafka.send(topic, key, payload).thenApply(result -> null);
        } catch (RuntimeException exception) {
            return CompletableFuture.failedFuture(exception);
        }
    }
}
