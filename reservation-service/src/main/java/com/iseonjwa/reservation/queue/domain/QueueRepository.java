package com.iseonjwa.reservation.queue.domain;

public interface QueueRepository {

    EnterOutcome enter(long scheduleId,
                       long userId, long openAtEpochMilli, long startsAtEpochMilli, long tokenTtlMillis, long keyExpireAtEpochSecond);

    PositionOutcome position(long scheduleId,
                             long userId, long tokenTtlMillis);

    void admit(long scheduleId,
               int admitPerSecond, long tokenTtlMillis, int cleanupLimit, long keyExpireAtEpochSecond);

    IssueTokenOutcome issueToken(long scheduleId,
                                 long userId, long tokenTtlMillis, long keyExpireAtEpochSecond);
}
