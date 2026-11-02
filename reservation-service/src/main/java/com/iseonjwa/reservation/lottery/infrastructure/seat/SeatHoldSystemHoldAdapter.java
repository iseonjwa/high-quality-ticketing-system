package com.iseonjwa.reservation.lottery.infrastructure.seat;

import com.iseonjwa.reservation.lottery.application.SystemHoldPort;
import com.iseonjwa.reservation.seathold.application.SystemSeatHoldService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

// 선점 규칙은 선점 모듈에 두고 추첨은 이 어댑터로만 부른다. 방향은 lottery → seathold이고 반대로는 부르지 않는다(ADR 0034)
@Component
@RequiredArgsConstructor
public class SeatHoldSystemHoldAdapter implements SystemHoldPort {

    private final SystemSeatHoldService systemSeatHoldService;

    @Override
    public LotteryHold hold(Long userId, Long scheduleId, List<Long> seatIds) {
        var result = systemSeatHoldService.holdLotterySeats(userId, scheduleId, seatIds);
        return new LotteryHold(result.reservationId(), result.totalAmount().toBigDecimal().longValueExact(),
                result.holdExpiresAt());
    }
}
