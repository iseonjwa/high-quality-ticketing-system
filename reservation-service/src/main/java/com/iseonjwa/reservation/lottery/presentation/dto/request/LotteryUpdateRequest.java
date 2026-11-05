package com.iseonjwa.reservation.lottery.presentation.dto.request;

import com.iseonjwa.reservation.concert.domain.entity.SeatGrade;
import com.iseonjwa.reservation.lottery.application.UpdateLotteryCommand;
import com.iseonjwa.reservation.lottery.domain.entity.Lottery;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

// 보낸 필드만 고치며, 범위는 설정 요청과 같고 값끼리의 관계는 합친 뒤 엔티티에서 검사한다
@Getter
@NoArgsConstructor
public class LotteryUpdateRequest {

    private SeatGrade sourceGrade;

    @Positive
    private Integer seatCount;

    @Min(0)
    private Integer waitlistCount;

    @Min(Lottery.MIN_PER_ENTRY)
    @Max(Lottery.MAX_PER_ENTRY)
    private Integer maxPerEntry;

    private Instant entryStartAt;

    private Instant entryEndAt;

    private Instant drawAt;

    public UpdateLotteryCommand toCommand(Long lotteryId) {
        return new UpdateLotteryCommand(
                lotteryId,
                sourceGrade,
                seatCount,
                waitlistCount,
                maxPerEntry,
                entryStartAt,
                entryEndAt,
                drawAt);
    }
}
