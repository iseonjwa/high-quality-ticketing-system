package com.iseonjwa.reservation.queue.domain;

public class QueueStorageUnavailableException extends RuntimeException {
    public QueueStorageUnavailableException(Throwable cause) {
        super(cause);
    }
}
