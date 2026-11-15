package com.iseonjwa.reservation.queue.domain;

public record IssueTokenOutcome(Type type, Long issuedAt) {

    public enum Type {
        ADMITTED,
        WAITING,
        NOT_JOINED
    }
}
