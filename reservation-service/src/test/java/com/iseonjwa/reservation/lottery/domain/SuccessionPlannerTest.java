package com.iseonjwa.reservation.lottery.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SuccessionPlannerTest {

    @Test
    void 남은_좌석만큼_순위_순으로_고르고_넘치는_매수는_건너뛴다() {
        var waitlist = List.of(new DrawEntrant(1, 2), new DrawEntrant(2, 1), new DrawEntrant(3, 2),
                new DrawEntrant(4, 1));

        assertEquals(List.of(new DrawEntrant(1, 2), new DrawEntrant(2, 1)), SuccessionPlanner.select(waitlist, 3));
        assertEquals(List.of(new DrawEntrant(2, 1)), SuccessionPlanner.select(waitlist, 1));
        assertEquals(List.of(), SuccessionPlanner.select(waitlist, 0));
    }
}
