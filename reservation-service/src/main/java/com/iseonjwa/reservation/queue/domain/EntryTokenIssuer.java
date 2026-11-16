package com.iseonjwa.reservation.queue.domain;

import java.time.Duration;

public interface EntryTokenIssuer {

    EntryToken issue(long userId, long scheduleId, long issuedAtEpochMillis, Duration ttl);

}
