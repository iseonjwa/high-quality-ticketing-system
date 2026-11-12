package com.iseonjwa.reservation.queue.domain;

import java.time.Duration;
import java.util.Optional;

public class QueueStorageUnavailableException extends RuntimeException {
    private final Duration retryAfter;

    public QueueStorageUnavailableException(Throwable cause) {
        this(null, cause);
    }

    public QueueStorageUnavailableException(Duration retryAfter, Throwable cause) {
        super(cause);
        this.retryAfter = retryAfter;
    }

    public Optional<Duration> retryAfter() {
        return Optional.ofNullable(retryAfter);
    }
}
