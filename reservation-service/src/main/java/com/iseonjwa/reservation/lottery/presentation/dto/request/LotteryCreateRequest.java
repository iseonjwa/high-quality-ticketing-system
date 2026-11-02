package com.iseonjwa.reservation.lottery.presentation.dto.request;

import com.iseonjwa.reservation.concert.domain.entity.SeatGrade;
import com.iseonjwa.reservation.lottery.application.CreateLotteryCommand;
import com.iseonjwa.reservation.lottery.domain.Lottery.LotterySettings;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

// 형식(값이 있는가, 범위)은 여기서, 값끼리의 관계(응모 기간 순서, LOTTERY 등급 금지)는 엔티티에서 검사한다
@Getter
@NoArgsConstructor
public class LotteryCreateRequest {

    @NotNull
    private SeatGrade sourceGrade;

    @NotNull
    @Positive
    private Integer seatCount;

    // 예비자 수. 기본은 당첨자의 50%(D1)지만 당첨자 수가 매수에 따라 달라 운영자가 정해 보낸다
    @NotNull
    @Min(0)
    private Integer waitlistCount;

    @NotNull
    @Min(1)
    @Max(2)
    private Integer maxPerEntry;

    // ISO-8601 형식이다. 예: "2026-12-01T01:00:00Z"
    @NotNull
    private Instant entryStartAt;

    @NotNull
    private Instant entryEndAt;

    @NotNull
    private Instant drawAt;

    public CreateLotteryCommand toCommand(Long scheduleId) {
        return new CreateLotteryCommand(scheduleId, new LotterySettings(sourceGrade, seatCount, waitlistCount,
                maxPerEntry, entryStartAt, entryEndAt, drawAt));
    }
}
