package com.iseonjwa.reservation.concert.presentation;

import com.iseonjwa.common.response.SuccessResponse;
import com.iseonjwa.reservation.concert.application.ScheduleSeatQueryService;
import com.iseonjwa.reservation.concert.application.ScheduleSeatsResult;
import com.iseonjwa.reservation.concert.presentation.dto.response.ScheduleSeatsResponse;
import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;

// 로그인과 입장 토큰(X-Entry-Token)은 Gateway가 검증한다(AccessPolicy, EntryTokenPolicy)
@RestController
@RequestMapping("/api/v1/schedules")
@RequiredArgsConstructor
public class ScheduleSeatController {

    private final ScheduleSeatQueryService scheduleSeatQueryService;
    private final Clock clock;

    // 회차 좌석 배치와 선택 가능 여부
    @GetMapping("/{scheduleId}/seats")
    public ResponseEntity<SuccessResponse<ScheduleSeatsResponse>> getSeats(@PathVariable Long scheduleId) {
        ScheduleSeatsResult seats = scheduleSeatQueryService.getSeats(scheduleId);
        return ResponseEntity.ok(SuccessResponse.of(200, "회차 좌석을 조회했습니다",
                ScheduleSeatsResponse.of(scheduleId, clock.instant(), seats), MDC.get("traceId")));
    }
}
