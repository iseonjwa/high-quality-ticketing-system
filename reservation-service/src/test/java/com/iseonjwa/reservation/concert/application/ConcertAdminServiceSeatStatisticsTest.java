package com.iseonjwa.reservation.concert.application;

import com.iseonjwa.reservation.concert.domain.entity.SeatGrade;
import com.iseonjwa.reservation.concert.domain.repository.ConcertRepository;
import com.iseonjwa.reservation.concert.domain.repository.ScheduleRepository;
import com.iseonjwa.reservation.concert.domain.repository.ScheduleSeatRepository;
import com.iseonjwa.reservation.concert.domain.repository.VenueRepository;
import com.iseonjwa.reservation.global.vo.Money;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.dao.QueryTimeoutException;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

// 회차 좌석을 만든 뒤 DB 통계를 갱신하는 순서와, 갱신이 실패해도 좌석 생성은 성공으로 답하는지 확인한다(ISE-284)
class ConcertAdminServiceSeatStatisticsTest {

    static final long SCHEDULE_ID = 7L;
    static final Map<SeatGrade, Money> PRICES = Map.of(SeatGrade.VIP, Money.of(165_000));

    final ScheduleSeatRepository scheduleSeatRepository = mock(ScheduleSeatRepository.class);
    final ScheduleSeatCreator scheduleSeatCreator = mock(ScheduleSeatCreator.class);
    final ConcertAdminService service = new ConcertAdminService(mock(ConcertRepository.class),
            mock(VenueRepository.class), mock(ScheduleRepository.class), scheduleSeatRepository, scheduleSeatCreator);

    @Test
    void 좌석을_만든_트랜잭션이_끝난_뒤에_통계를_갱신한다() {
        when(scheduleSeatCreator.create(SCHEDULE_ID, PRICES)).thenReturn(1_500);

        assertEquals(1_500, service.createScheduleSeats(SCHEDULE_ID, PRICES));

        InOrder order = inOrder(scheduleSeatCreator, scheduleSeatRepository);
        order.verify(scheduleSeatCreator).create(SCHEDULE_ID, PRICES);
        order.verify(scheduleSeatRepository).refreshStatistics();
    }

    @Test
    void 통계_갱신이_실패해도_이미_만든_좌석_수를_돌려준다() {
        when(scheduleSeatCreator.create(SCHEDULE_ID, PRICES)).thenReturn(1_500);
        doThrow(new QueryTimeoutException("ANALYZE 실패")).when(scheduleSeatRepository).refreshStatistics();

        assertEquals(1_500, service.createScheduleSeats(SCHEDULE_ID, PRICES));
    }
}
