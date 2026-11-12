package com.iseonjwa.reservation.seathold.presentation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SeatHoldModeTest {
    @Test
    void 설정값은_대소문자와_앞뒤_공백을_가리지_않는다() {
        assertEquals(SeatHoldMode.SYNC, SeatHoldMode.from("sync"));
        assertEquals(SeatHoldMode.ASYNC, SeatHoldMode.from(" ASYNC "));
    }

    @Test
    void 모르는_값이면_기동을_실패시킨다() {
        assertThrows(IllegalArgumentException.class, () -> SeatHoldMode.from("asnyc"));
    }
}
