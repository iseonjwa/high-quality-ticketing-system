package com.iseonjwa.common.messaging.outbox;

import java.util.concurrent.CompletableFuture;

/**
 * Outbox 행을 브로커로 보내는 포트. 반환한 Future는 브로커 확인을 받으면 완료되고, 확인하지 못하면 예외로 완료된다.
 * 한 번의 실행에서 같은 키의 행은 하나뿐이라 여러 건을 한꺼번에 보내도 키별 순서가 깨지지 않는다.
 */
public interface OutboxEventSender {
    CompletableFuture<Void> send(String topic, String key, String payload);
}
