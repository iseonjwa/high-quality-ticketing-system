package com.iseonjwa.common.auth;

import java.time.Duration;

// 입장 토큰 claim 계약. 발급(Reservation)과 검증(Gateway)이 같이 쓴다
public final class EntryTokenClaims {
    public static final String TYPE = "typ";
    public static final String TYPE_VALUE = "entry";
    public static final String AUDIENCE_VALUE = "gateway";
    public static final String SCHEDULE_ID = "scheduleId";
    public static final Duration TTL = Duration.ofMinutes(10);

    private EntryTokenClaims() {}
}