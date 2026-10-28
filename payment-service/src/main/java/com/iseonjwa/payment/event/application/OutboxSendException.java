package com.iseonjwa.payment.event.application;

public class OutboxSendException extends RuntimeException {
    public OutboxSendException(String message, Throwable cause) {
        super(message, cause);
    }
}
