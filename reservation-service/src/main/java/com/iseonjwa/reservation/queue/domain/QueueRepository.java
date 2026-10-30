package com.iseonjwa.reservation.queue.domain;

public interface QueueRepository {

    EnterOutcome enter(long scheduleId,
                       long userId, long openAtEpochMilli, long tokenTtlMillis, long keyExpireAtEpochSecond);
}
