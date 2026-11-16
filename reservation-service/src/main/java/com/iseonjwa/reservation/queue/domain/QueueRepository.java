package com.iseonjwa.reservation.queue.domain;

import java.util.Optional;

public interface QueueRepository {

    EnterOutcome enter(long scheduleId,
                       long userId, long openAtEpochMilli, long startsAtEpochMilli, long tokenTtlMillis, long keyExpireAtEpochSecond, long turnTtlMillis);

    PositionOutcome position(long scheduleId,
                             long userId, long tokenTtlMillis, long turnTtlMillis);

    void admit(long scheduleId,
               int admitPerSecond, long tokenTtlMillis, int cleanupLimit, long keyExpireAtEpochSecond);

    IssueTokenOutcome issueToken(long scheduleId,
                                 long userId, long tokenTtlMillis, long keyExpireAtEpochSecond, long turnTtlMillis);

    Optional<Long> issuedAt(long scheduleId, long userId);

    void discardToken(long scheduleId, long userId, long issuedAtEpochMillis);

    QueueStats stats(long scheduleId, long tokenTtlMillis);
}
