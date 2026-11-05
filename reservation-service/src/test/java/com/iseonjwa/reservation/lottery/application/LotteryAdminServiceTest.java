package com.iseonjwa.reservation.lottery.application;

import com.iseonjwa.common.persistence.CurrentActorProvider;
import com.iseonjwa.reservation.concert.domain.entity.Schedule;
import com.iseonjwa.reservation.concert.domain.entity.SeatGrade;
import com.iseonjwa.reservation.concert.domain.repository.ScheduleRepository;
import com.iseonjwa.reservation.lottery.domain.entity.Lottery;
import com.iseonjwa.reservation.lottery.domain.entity.Lottery.LotterySettings;
import com.iseonjwa.reservation.lottery.domain.repository.LotteryRepository;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// 쿼리가 또 어긋나도 잘못된 전환이 커밋되지 않게 막는 검사를 확인한다. 정상 수와 모자란 경우는 AdminLotteryApiTest가 본다.
class LotteryAdminServiceTest {
    private static final Instant NOW = Instant.parse("2026-11-06T00:00:00Z");
    private static final long SCHEDULE_ID = 1L;
    private static final int REQUESTED = 1;
    private static final int OVER_CONVERTED = 3;

    private final LotteryRepository lotteryRepository = mock(LotteryRepository.class);
    private final ScheduleRepository scheduleRepository = mock(ScheduleRepository.class);
    private final LotterySeatPort lotterySeatPort = mock(LotterySeatPort.class);
    private final CurrentActorProvider currentActorProvider = mock(CurrentActorProvider.class);

    private final LotteryAdminService service = new LotteryAdminService(lotteryRepository, scheduleRepository,
            lotterySeatPort, currentActorProvider, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void 요청보다_많이_전환되면_예외로_트랜잭션을_되돌리고_추첨을_저장하지_않는다() {
        Schedule schedule = mock(Schedule.class);
        when(schedule.getOpenAt()).thenReturn(NOW.plus(Duration.ofDays(10)));
        when(schedule.getStartsAt()).thenReturn(NOW.plus(Duration.ofDays(30)));
        when(scheduleRepository.findByIdForUpdate(SCHEDULE_ID)).thenReturn(Optional.of(schedule));
        when(lotteryRepository.existsByScheduleId(SCHEDULE_ID)).thenReturn(false);
        when(currentActorProvider.currentActorId()).thenReturn(Optional.of(9004L));
        when(lotterySeatPort.convertToLottery(SCHEDULE_ID, SeatGrade.S, REQUESTED, 9004L)).thenReturn(OVER_CONVERTED);

        var settings = new LotterySettings(SeatGrade.S, REQUESTED, 1, 2, NOW.plus(Duration.ofDays(1)),
                NOW.plus(Duration.ofDays(2)), NOW.plus(Duration.ofDays(3)));
        var command = new CreateLotteryCommand(SCHEDULE_ID, settings);

        assertThrows(IllegalStateException.class, () -> service.create(command));
        verify(lotteryRepository, never()).save(any(Lottery.class));
    }
}
