package com.iseonjwa.reservation.queue.domain;

public interface EntryTokenIssuer {

    EntryToken issue(long userId, long scheduleId, long issuedAtEpochMillis);

}
