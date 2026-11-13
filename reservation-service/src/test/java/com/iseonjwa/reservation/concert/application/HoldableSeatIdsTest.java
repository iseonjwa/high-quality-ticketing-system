package com.iseonjwa.reservation.concert.application;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HoldableSeatIdsTest {

    // 쿼리가 정렬해 주지 않아도 이진 탐색이 맞아야 한다
    @Test
    void 정렬되지_않은_ID로_만들어도_있는_좌석만_찾는다() {
        HoldableSeatIds holdable = HoldableSeatIds.of(List.of(30L, 10L, 20L));

        assertTrue(holdable.contains(10L));
        assertTrue(holdable.contains(20L));
        assertTrue(holdable.contains(30L));
        assertFalse(holdable.contains(15L));
        assertFalse(holdable.contains(40L));
    }

    @Test
    void 선점_가능한_좌석이_없으면_아무것도_찾지_않는다() {
        assertFalse(HoldableSeatIds.of(List.of()).contains(1L));
    }
}
