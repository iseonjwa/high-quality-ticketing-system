package com.iseonjwa.reservation.queue.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

// 판매 중인 회차들의 대기열 통계를 합친다 (ISE-61)
class QueueStatsTest {

    @Test
    void 항목별로_더한_새_값을_돌려주고_원래_값은_바꾸지_않는다() {
        var a = new QueueStats(10, 7, 3, 2, 5);
        var b = new QueueStats(100, 70, 30, 20, 50);

        assertEquals(new QueueStats(110, 77, 33, 22, 55), a.plus(b));
        assertEquals(new QueueStats(10, 7, 3, 2, 5), a);
    }

    @Test
    void EMPTY에_더하면_그대로다() {
        var a = new QueueStats(10, 7, 3, 2, 5);

        assertEquals(a, QueueStats.EMPTY.plus(a));
        assertEquals(new QueueStats(0, 0, 0, 0, 0), QueueStats.EMPTY);
    }
}
