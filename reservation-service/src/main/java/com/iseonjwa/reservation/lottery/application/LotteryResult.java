package com.iseonjwa.reservation.lottery.application;

import com.iseonjwa.reservation.lottery.domain.DrawTimeWindow;
import com.iseonjwa.reservation.lottery.domain.Lottery;
import com.iseonjwa.reservation.lottery.domain.Lottery.LotterySettings;
import com.iseonjwa.reservation.lottery.domain.LotteryStatus;

import java.util.List;

/**
 * 추첨 설정·수정 결과.
 *
 * @param warnings 막지 않는 정책 위반처럼 운영자가 확인할 점(예: 권장 시간대 밖 추첨)
 */
public record LotteryResult(
        Long lotteryId,
        Long scheduleId,
        LotteryStatus status,
        LotterySettings settings,
        List<String> warnings) {

    public static final String DRAW_AT_OUTSIDE_LOW_TRAFFIC_WINDOW = "DRAW_AT_OUTSIDE_LOW_TRAFFIC_WINDOW";

    static LotteryResult of(Lottery lottery) {
        LotterySettings settings = lottery.settings();
        List<String> warnings = DrawTimeWindow.contains(settings.drawAt())
                ? List.of()
                : List.of(DRAW_AT_OUTSIDE_LOW_TRAFFIC_WINDOW);

        return new LotteryResult(
                lottery.getId(),
                lottery.getScheduleId(),
                lottery.getStatus(),
                settings,
                warnings);
    }
}
