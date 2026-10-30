package com.iseonjwa.reservation.concert.domain.entity;

import com.iseonjwa.common.response.BusinessException;
import com.iseonjwa.reservation.global.vo.Money;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ScheduleTest {
    private static final Instant STARTS_AT = Instant.parse("2026-11-20T10:00:00Z");
    private static final Instant OPEN_AT = Instant.parse("2026-11-01T11:00:00Z");

    private final Concert concert = new Concert("공연", null);
    private final Venue venue = new Venue("KSPO DOME");

    @Test
    void 예매_오픈이_공연_시작보다_앞이면_회차를_만든다() {
        var schedule = Schedule.create(concert, venue, STARTS_AT, OPEN_AT, 4, 5);

        assertEquals(4, schedule.getMaxPerUser());
        assertEquals(5, schedule.getHoldMinutes());
    }

    @Test
    void 예매_오픈이_공연_시작과_같거나_뒤면_거절한다() {
        assertThrows(BusinessException.class,
                () -> Schedule.create(concert, venue, STARTS_AT, STARTS_AT, 4, 5));
        assertThrows(BusinessException.class,
                () -> Schedule.create(concert, venue, OPEN_AT, STARTS_AT, 4, 5));
    }

    @Test
    void 매수_한도와_선점_시간은_1_이상이어야_한다() {
        assertThrows(BusinessException.class,
                () -> Schedule.create(concert, venue, STARTS_AT, OPEN_AT, 0, 5));
        assertThrows(BusinessException.class,
                () -> Schedule.create(concert, venue, STARTS_AT, OPEN_AT, 4, 0));
    }

    @Test
    void 회차_좌석은_공연장_좌석의_구역과_등급을_복사하고_AVAILABLE로_시작한다() {
        var schedule = Schedule.create(concert, venue, STARTS_AT, OPEN_AT, 4, 5);
        var venueSeat = new VenueSeat(venue, "A", "3", 12, SeatGrade.VIP);

        var seat = ScheduleSeat.create(schedule, venueSeat, Money.of(165_000));

        assertEquals("A", seat.getSection());
        assertEquals(SeatGrade.VIP, seat.getGrade());
        assertEquals(Money.of(165_000), seat.getPrice());
        assertEquals(ScheduleSeatStatus.AVAILABLE, seat.getStatus());
    }

    @Test
    void 다른_공연장의_좌석으로는_회차_좌석을_만들_수_없다() {
        var schedule = Schedule.create(concert, venue, STARTS_AT, OPEN_AT, 4, 5);
        var otherVenueSeat = new VenueSeat(new Venue("다른 공연장"), "A", "3", 12, SeatGrade.VIP);

        assertThrows(BusinessException.class,
                () -> ScheduleSeat.create(schedule, otherVenueSeat, Money.of(165_000)));
    }
}
