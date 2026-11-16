package com.iseonjwa.reservation.concert.application;

import com.iseonjwa.reservation.concert.domain.entity.SeatGrade;
import com.iseonjwa.reservation.concert.domain.repository.SeatLayoutRow;
import com.iseonjwa.reservation.global.vo.Money;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

// 같은 배치·같은 1초 캐시 값이면 같은 결과 객체를 준다(ISE-264). 화면 계층이 이 객체로 직렬화한 좌석 배열을 다시 쓴다
class ScheduleSeatQueryServiceResultReuseTest {

    static final long SCHEDULE_A = 7L;
    static final long SCHEDULE_B = 8L;

    final ScheduleSeatLayoutCache layoutCache = mock(ScheduleSeatLayoutCache.class);
    final ScheduleSeatReader scheduleSeatReader = mock(ScheduleSeatReader.class);
    final ScheduleSeatQueryService service = new ScheduleSeatQueryService(layoutCache, scheduleSeatReader);

    @BeforeEach
    void setUp() {
        when(layoutCache.get(SCHEDULE_A)).thenReturn(List.of(seat(1L), seat(2L)));
        when(layoutCache.get(SCHEDULE_B)).thenReturn(List.of(seat(3L)));
        when(scheduleSeatReader.readHoldableSeatIds(SCHEDULE_A)).thenReturn(List.of(1L, 2L), List.of(1L));
        when(scheduleSeatReader.readHoldableSeatIds(SCHEDULE_B)).thenReturn(List.of(3L));
    }

    @Test
    void 일초_안에는_같은_결과_객체를_준다() {
        assertSame(service.getSeats(SCHEDULE_A), service.getSeats(SCHEDULE_A));
    }

    @Test
    void 일초가_지나면_새로_읽은_상태로_새_결과를_만든다() throws Exception {
        ScheduleSeatsResult before = service.getSeats(SCHEDULE_A);

        Thread.sleep(1_100);
        ScheduleSeatsResult after = service.getSeats(SCHEDULE_A);

        assertNotSame(before, after);
        assertArrayEquals(new boolean[]{true, true}, before.getAvailable());
        assertArrayEquals(new boolean[]{true, false}, after.getAvailable());
    }

    @Test
    void 다른_회차의_결과는_섞이지_않고_밀어내지도_않는다() {
        ScheduleSeatsResult a = service.getSeats(SCHEDULE_A);
        ScheduleSeatsResult b = service.getSeats(SCHEDULE_B);

        assertEquals(3L, b.getSeats().getFirst().seatId());
        assertSame(a, service.getSeats(SCHEDULE_A));
    }

    private static SeatLayoutRow seat(long seatId) {
        return new SeatLayoutRow(seatId, "A", SeatGrade.R, Money.ZERO, 1, (int) seatId);
    }
}
