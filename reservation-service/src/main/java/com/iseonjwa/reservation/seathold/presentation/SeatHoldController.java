package com.iseonjwa.reservation.seathold.presentation;

import com.iseonjwa.common.response.BusinessException;
import com.iseonjwa.common.response.CommonErrorCode;
import com.iseonjwa.common.response.SuccessResponse;
import com.iseonjwa.reservation.seathold.application.HoldSeatsCommand;
import com.iseonjwa.reservation.seathold.application.SyncSeatHoldService;
import com.iseonjwa.reservation.seathold.presentation.SeatHoldDtos.HoldRequest;
import com.iseonjwa.reservation.seathold.presentation.SeatHoldDtos.HoldResponse;
import jakarta.validation.Valid;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 좌석 선점 API(contracts.md 1절). 입장 토큰({@code X-Entry-Token})의 서명·만료·사용자·회차는 Gateway가 검증하므로
 * 여기서 다시 보지 않는다(ISE-20). 사용자 ID는 Gateway가 검증해 전달한 값만 쓴다.
 */
@RestController
public class SeatHoldController {
    // UUID.fromString은 "1-1-1-1-1"처럼 자리수가 모자란 값도 받아들여서 표준 형식(8-4-4-4-12)을 먼저 확인한다
    private static final Pattern UUID_FORMAT = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private final SyncSeatHoldService seatHoldService;

    public SeatHoldController(SyncSeatHoldService seatHoldService) {
        this.seatHoldService = seatHoldService;
    }

    @PostMapping("/api/v1/schedules/{scheduleId}/reservations")
    public ResponseEntity<SuccessResponse<HoldResponse>> hold(@RequestAttribute Long currentUserId,
            @RequestHeader("Idempotency-Key") String idempotencyKey, @PathVariable Long scheduleId,
            @Valid @RequestBody HoldRequest request) {
        if (scheduleId <= 0) {
            throw new BusinessException(CommonErrorCode.VALIDATION_FAILED);
        }
        var result = seatHoldService.hold(
                new HoldSeatsCommand(currentUserId, parseKey(idempotencyKey), scheduleId, request.seatIds()));
        return ResponseEntity.status(201)
                .body(SuccessResponse.of(201, "선점되었습니다", HoldResponse.from(result), MDC.get("traceId")));
    }

    private static UUID parseKey(String value) {
        if (!UUID_FORMAT.matcher(value).matches()) {
            throw new BusinessException(CommonErrorCode.VALIDATION_FAILED);
        }
        return UUID.fromString(value);
    }
}
