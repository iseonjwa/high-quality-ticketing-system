package com.iseonjwa.reservation.concert.presentation;

import com.iseonjwa.common.identity.Roles;
import com.iseonjwa.common.response.SuccessResponse;
import com.iseonjwa.common.web.RequireRole;
import com.iseonjwa.reservation.concert.application.ConcertAdminService;
import com.iseonjwa.reservation.concert.presentation.dto.request.ScheduleSeatCreateRequest;
import com.iseonjwa.reservation.concert.presentation.dto.response.ScheduleSeatCreateResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// Gateway가 먼저 ADMIN 경로를 막지만, 서비스에서도 @RequireRole로 한 번 더 확인한다
@RestController
@RequestMapping("/api/v1/admin/schedules")
@RequireRole(Roles.ADMIN)
@RequiredArgsConstructor
public class AdminScheduleController {

    private final ConcertAdminService concertAdminService;

    // 회차 좌석 생성
    @PostMapping("/{scheduleId}/seats")
    public ResponseEntity<SuccessResponse<ScheduleSeatCreateResponse>> createScheduleSeats(
            @PathVariable Long scheduleId, @Valid @RequestBody ScheduleSeatCreateRequest request) {
        int createdSeats = concertAdminService.createScheduleSeats(scheduleId, request.toPriceMap());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(SuccessResponse.of(201, "회차 좌석을 만들었습니다",
                        new ScheduleSeatCreateResponse(scheduleId, createdSeats), MDC.get("traceId")));
    }
}
