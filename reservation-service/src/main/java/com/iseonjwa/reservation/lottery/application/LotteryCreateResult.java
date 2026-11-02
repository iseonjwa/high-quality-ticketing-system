package com.iseonjwa.reservation.lottery.application;

import com.iseonjwa.reservation.concert.domain.entity.SeatGrade;
import com.iseonjwa.reservation.lottery.domain.LotteryStatus;

import java.util.List;

/**
 * @param warnings 설정은 했지만 운영자가 확인할 점. 막지 않는 정책 위반이다(예: 권장 시간대 밖 추첨)
 */
public record LotteryCreateResult(
        Long lotteryId,
        Long scheduleId,
        SeatGrade sourceGrade,
        int seatCount,
        LotteryStatus status,
        List<String> warnings) {

    public static final String DRAW_AT_OUTSIDE_LOW_TRAFFIC_WINDOW = "DRAW_AT_OUTSIDE_LOW_TRAFFIC_WINDOW";
}
