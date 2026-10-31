package com.iseonjwa.reservation.concert.presentation;

import com.iseonjwa.common.identity.Roles;
import com.iseonjwa.common.response.SuccessResponse;
import com.iseonjwa.common.web.RequireRole;
import com.iseonjwa.reservation.concert.application.ConcertAdminService;
import com.iseonjwa.reservation.concert.presentation.dto.request.ConcertCreateRequest;
import com.iseonjwa.reservation.concert.presentation.dto.request.ScheduleCreateRequest;
import com.iseonjwa.reservation.concert.presentation.dto.response.ConcertCreateResponse;
import com.iseonjwa.reservation.concert.presentation.dto.response.ScheduleCreateResponse;
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
@RequestMapping("/api/v1/admin/concerts")
@RequireRole(Roles.ADMIN)
@RequiredArgsConstructor
public class AdminConcertController {

    private final ConcertAdminService concertAdminService;

    // 공연 등록
    @PostMapping
    public ResponseEntity<SuccessResponse<ConcertCreateResponse>> createConcert(@Valid @RequestBody ConcertCreateRequest request) {
        Long concertId = concertAdminService.registerConcert(request.getTitle(), request.getDescription());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(SuccessResponse.of(201, "공연을 등록했습니다", new ConcertCreateResponse(concertId),
                        MDC.get("traceId")));
    }

    // 회차 등록
    @PostMapping("/{concertId}/schedules")
    public ResponseEntity<SuccessResponse<ScheduleCreateResponse>> createSchedule(
            @PathVariable Long concertId, @Valid @RequestBody ScheduleCreateRequest request) {
        Long scheduleId = concertAdminService.registerSchedule(concertId, request.getVenueId(),
                request.getStartsAt(), request.getOpenAt(), request.getMaxPerUser(), request.getHoldMinutes());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(SuccessResponse.of(201, "회차를 등록했습니다", new ScheduleCreateResponse(scheduleId),
                        MDC.get("traceId")));
    }
}
