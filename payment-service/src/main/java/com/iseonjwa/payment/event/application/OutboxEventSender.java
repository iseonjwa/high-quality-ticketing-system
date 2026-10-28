package com.iseonjwa.payment.event.application;

/**
 * Outbox 행을 브로커로 보내는 포트. 브로커 확인을 받을 때까지 기다리고, 확인하지 못하면 예외를 던진다.
 */
public interface OutboxEventSender {
    void send(String topic, String key, String payload);
}
