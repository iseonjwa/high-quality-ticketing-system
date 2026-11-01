package com.iseonjwa.reservation.queue.domain;

public record PositionOutcome(Type type, Long seq, Long admitted) {

    public enum Type {
        TOKEN_ISSUED,
        IN_QUEUE,
        NOT_JOINED
    }
}
