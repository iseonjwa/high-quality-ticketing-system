package com.iseonjwa.reservation.lottery.application;

import com.iseonjwa.reservation.lottery.domain.Lottery.LotterySettings;

public record CreateLotteryCommand(Long scheduleId, LotterySettings settings) {
}
