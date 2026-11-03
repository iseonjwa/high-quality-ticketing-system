package com.iseonjwa.reservation.queue.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// 판매 중 판단: 오픈 시각 정각부터 판매 중, 공연 시작 정각부터 판매 끝 (concert Schedule.saleStatusAt과 같은 경계)
class ScheduleTimesTest {

    static final Instant OPEN_AT = Instant.parse("2026-11-10T10:00:00Z");
    static final Instant STARTS_AT = Instant.parse("2026-11-20T19:00:00Z");

    final ScheduleTimes schedule = new ScheduleTimes(31L, OPEN_AT, STARTS_AT);

    @Test
    void 오픈_전이면_판매_중이_아니다() {
        assertFalse(schedule.isOnSale(OPEN_AT.minusMillis(1)));
    }

    @Test
    void 오픈_시각_정각부터_판매_중이다() {
        assertTrue(schedule.isOnSale(OPEN_AT));
    }

    @Test
    void 공연_시작_직전까지_판매_중이다() {
        assertTrue(schedule.isOnSale(STARTS_AT.minusMillis(1)));
    }

    @Test
    void 공연_시작_정각부터_판매_중이_아니다() {
        assertFalse(schedule.isOnSale(STARTS_AT));
    }
}
