package com.iseonjwa.reservation.lottery.infrastructure.seat;

import com.iseonjwa.reservation.lottery.application.SystemHoldPort;
import com.iseonjwa.reservation.seathold.application.SystemSeatHoldService;
import com.iseonjwa.reservation.seathold.application.SystemSeatHoldService.SystemHoldResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

// 선점 규칙은 선점 모듈에 두고 추첨은 이 어댑터로만 부르며, 호출 방향은 lottery → seathold뿐이다(ADR 0034)
@Component
@RequiredArgsConstructor
public class SeatHoldSystemHoldAdapter implements SystemHoldPort {

    private final SystemSeatHoldService systemSeatHoldService;

    @Override
    public LotteryHold hold(Long userId, Long scheduleId, List<Long> seatIds) {
        SystemHoldResult result = systemSeatHoldService.holdLotterySeats(userId, scheduleId, seatIds);

        return new LotteryHold(
                result.reservationId(),
                result.totalAmount().toBigDecimal().longValueExact(),
                result.holdExpiresAt());
    }
}
