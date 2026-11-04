package com.iseonjwa.reservation.queue.domain;

public record IssueTokenOutcome(Type type, Long issuedAt) {

    public enum Type {
        TOKEN_ISSUED,
        NOT_READY,
        NOT_JOINED
    }
}
