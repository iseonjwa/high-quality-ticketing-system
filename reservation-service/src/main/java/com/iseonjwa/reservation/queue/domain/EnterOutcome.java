package com.iseonjwa.reservation.queue.domain;

public record EnterOutcome(Type type, Long seq, Long admitted) {

    public enum Type {
        SALE_NOT_OPEN,
        TOKEN_ISSUED,
        ALREADY_WAITING,
        JOINED
    }


}
