package com.iseonjwa.reservation.queue.domain;

import java.time.Instant;

public record ScheduleTimes(long id, Instant openAt, Instant startsAt) {

    public boolean isOnSale(Instant now) {
        return !openAt.isAfter(now) && startsAt.isAfter(now);
    }
}
