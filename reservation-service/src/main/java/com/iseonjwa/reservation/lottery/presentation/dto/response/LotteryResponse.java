package com.iseonjwa.reservation.lottery.presentation.dto.response;

import com.iseonjwa.reservation.lottery.application.LotteryResult;
import com.iseonjwa.reservation.lottery.domain.Lottery.LotterySettings;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.Instant;
import java.util.List;

@Getter
@AllArgsConstructor
public class LotteryResponse {

    private Long lotteryId;
    private Long scheduleId;
    private String status;
    private String sourceGrade;
    private int seatCount;
    private int waitlistCount;
    private int maxPerEntry;
    private Instant entryStartAt;
    private Instant entryEndAt;
    private Instant drawAt;
    private List<String> warnings;

    public static LotteryResponse from(LotteryResult result) {
        LotterySettings settings = result.settings();

        return new LotteryResponse(
                result.lotteryId(),
                result.scheduleId(),
                result.status().name(),
                settings.sourceGrade().name(),
                settings.seatCount(),
                settings.waitlistCount(),
                settings.maxPerEntry(),
                settings.entryStartAt(),
                settings.entryEndAt(),
                settings.drawAt(),
                result.warnings());
    }
}
