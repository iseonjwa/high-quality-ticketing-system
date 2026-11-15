package com.iseonjwa.reservation.concert.application;

import com.iseonjwa.reservation.concert.domain.entity.SeatGrade;
import com.iseonjwa.reservation.concert.domain.repository.SeatLayoutRow;
import com.iseonjwa.reservation.global.vo.Money;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// 선점 사전 거절용 읽기(ISE-281)가 1초 캐시만 보고 DB를 새로 읽지 않는지 확인한다
class ScheduleSeatQueryServiceAnyNotHoldableTest {

    static final long SCHEDULE_ID = 7L;

    final ScheduleSeatLayoutCache layoutCache = mock(ScheduleSeatLayoutCache.class);
    final ScheduleSeatReader scheduleSeatReader = mock(ScheduleSeatReader.class);
    final ScheduleSeatQueryService service = new ScheduleSeatQueryService(layoutCache, scheduleSeatReader);

    @BeforeEach
    void setUp() {
        // 회차 좌석 1·2·3 중 2는 이미 잡혔다
        when(layoutCache.get(SCHEDULE_ID)).thenReturn(List.of(seat(1L), seat(2L), seat(3L)));
        when(scheduleSeatReader.readHoldableSeatIds(SCHEDULE_ID)).thenReturn(List.of(1L, 3L));
    }

    @Test
    void 캐시가_있으면_요청_좌석이_모두_선점_가능할_때만_false다() {
        service.getSeats(SCHEDULE_ID);

        assertFalse(service.anyNotHoldable(SCHEDULE_ID, List.of(1L, 3L)));
    }

    @Test
    void 캐시가_있으면_이미_잡혔거나_회차에_없는_좌석이_하나라도_있으면_true다() {
        service.getSeats(SCHEDULE_ID);

        assertTrue(service.anyNotHoldable(SCHEDULE_ID, List.of(1L, 2L)));
        assertTrue(service.anyNotHoldable(SCHEDULE_ID, List.of(99L)));
    }

    @Test
    void 캐시가_있어도_판단할_때_DB를_다시_읽지_않는다() {
        service.getSeats(SCHEDULE_ID);
        clearInvocations(scheduleSeatReader);

        service.anyNotHoldable(SCHEDULE_ID, List.of(2L));

        verify(scheduleSeatReader, never()).readHoldableSeatIds(anyLong());
    }

    @Test
    void 그_회차를_아무도_조회하지_않아_캐시가_없으면_DB를_읽지_않고_false다() {
        assertFalse(service.anyNotHoldable(SCHEDULE_ID, List.of(2L)));

        verify(scheduleSeatReader, never()).readHoldableSeatIds(anyLong());
    }

    @Test
    void 캐시가_1초를_넘겨_낡았으면_DB를_읽지_않고_false다() throws Exception {
        service.getSeats(SCHEDULE_ID);
        clearInvocations(scheduleSeatReader);
        Thread.sleep(1_100);

        assertFalse(service.anyNotHoldable(SCHEDULE_ID, List.of(2L)));
        verify(scheduleSeatReader, never()).readHoldableSeatIds(anyLong());
    }

    private static SeatLayoutRow seat(long seatId) {
        return new SeatLayoutRow(seatId, "A", SeatGrade.R, Money.ZERO, 1, (int) seatId);
    }
}
