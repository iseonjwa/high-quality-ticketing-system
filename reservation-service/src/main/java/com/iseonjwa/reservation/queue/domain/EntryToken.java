package com.iseonjwa.reservation.queue.domain;

import java.time.Instant;

public record EntryToken(String value, Instant expiresAt) {
}
