package com.iseonjwa.reservation.concert.domain.entity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class VenueSeatTest {
    private final Venue venue = new Venue("KSPO DOME");

    @Test
    void 공연장_좌석은_판매_등급으로_만든다() {
        var seat = new VenueSeat(venue, "A", 1, 1, SeatGrade.S);

        assertEquals(SeatGrade.S, seat.getGrade());
    }

    @Test
    void 공연장_좌석_등급에_LOTTERY를_쓰면_거절한다() {
        assertThrows(IllegalArgumentException.class, () -> new VenueSeat(venue, "A", 1, 1, SeatGrade.LOTTERY));
    }
}
