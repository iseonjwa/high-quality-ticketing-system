package com.iseonjwa.reservation.seathold.application;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ReservationNoTest {
    @Test
    void 한국_날짜와_영숫자_8자로_컬럼_길이_안에서_만든다() {
        // UTC 15시는 한국 날짜로 다음 날이다
        var number = ReservationNo.next(Instant.parse("2026-11-01T15:00:00Z"), new Random(1));

        assertTrue(number.matches("R20261102-[0-9A-HJ-NP-Z]{8}"), number);
        assertTrue(number.length() <= 20, number);
    }
}
