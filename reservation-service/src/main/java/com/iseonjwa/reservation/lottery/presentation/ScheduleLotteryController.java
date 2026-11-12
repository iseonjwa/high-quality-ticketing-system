package com.iseonjwa.reservation.lottery.presentation;

import com.iseonjwa.common.response.SuccessResponse;
import com.iseonjwa.reservation.lottery.application.LotteryEntryService;
import com.iseonjwa.reservation.lottery.domain.entity.Lottery;
import com.iseonjwa.reservation.lottery.presentation.dto.LotteryEntryDtos.LotteryInfoResponse;
import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * 공연 상세가 회차만 알고 추첨 화면으로 이동할 수 있게 회차로 추첨을 찾는다(ISE-227).
 */
@RestController
@RequiredArgsConstructor
public class ScheduleLotteryController {

    private final LotteryEntryService lotteryEntryService;

    // 추첨 단건 조회와 같은 공개 정보를 돌려줘 화면이 같은 타입을 쓴다
    @GetMapping("/api/v1/schedules/{scheduleId}/lottery")
    public ResponseEntity<SuccessResponse<LotteryInfoResponse>> lotteryOfSchedule(@PathVariable Long scheduleId) {
        Lottery lottery = lotteryEntryService.findLotteryOfSchedule(scheduleId);

        return ResponseEntity.ok(SuccessResponse.of(
                200,
                "추첨 정보입니다",
                LotteryInfoResponse.from(lottery),
                MDC.get("traceId")));
    }
}
