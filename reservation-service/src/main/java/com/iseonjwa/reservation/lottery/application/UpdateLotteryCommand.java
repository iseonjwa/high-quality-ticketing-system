package com.iseonjwa.reservation.lottery.application;

import com.iseonjwa.reservation.concert.domain.entity.SeatGrade;
import com.iseonjwa.reservation.lottery.domain.entity.Lottery.LotterySettings;

import java.time.Instant;

/**
 * 추첨 설정 수정. null인 필드는 지금 값을 그대로 쓴다.
 */
public record UpdateLotteryCommand(
        Long lotteryId,
        SeatGrade sourceGrade,
        Integer seatCount,
        Integer waitlistCount,
        Integer maxPerEntry,
        Instant entryStartAt,
        Instant entryEndAt,
        Instant drawAt) {

    LotterySettings mergeInto(LotterySettings current) {
        return new LotterySettings(
                sourceGrade != null ? sourceGrade : current.sourceGrade(),
                seatCount != null ? seatCount : current.seatCount(),
                waitlistCount != null ? waitlistCount : current.waitlistCount(),
                maxPerEntry != null ? maxPerEntry : current.maxPerEntry(),
                entryStartAt != null ? entryStartAt : current.entryStartAt(),
                entryEndAt != null ? entryEndAt : current.entryEndAt(),
                drawAt != null ? drawAt : current.drawAt());
    }
}
