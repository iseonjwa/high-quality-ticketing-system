package com.iseonjwa.reservation.lottery.presentation.dto.response;

import com.iseonjwa.reservation.lottery.application.LotteryCreateResult;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;

@Getter
@AllArgsConstructor
public class LotteryCreateResponse {

    private Long lotteryId;
    private Long scheduleId;
    private String sourceGrade;
    private int seatCount;
    private String status;
    private List<String> warnings;

    public static LotteryCreateResponse from(LotteryCreateResult result) {
        return new LotteryCreateResponse(result.lotteryId(), result.scheduleId(), result.sourceGrade().name(),
                result.seatCount(), result.status().name(), result.warnings());
    }
}
