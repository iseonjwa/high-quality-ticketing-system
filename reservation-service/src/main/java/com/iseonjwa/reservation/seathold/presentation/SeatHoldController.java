package com.iseonjwa.reservation.seathold.presentation;

import com.iseonjwa.common.response.BusinessException;
import com.iseonjwa.common.response.CommonErrorCode;
import com.iseonjwa.common.response.SuccessResponse;
import com.iseonjwa.reservation.seathold.application.AsyncSeatHoldService;
import com.iseonjwa.reservation.seathold.application.HoldSeatsCommand;
import com.iseonjwa.reservation.seathold.application.SyncHoldOutcome;
import com.iseonjwa.reservation.seathold.application.SyncSeatHoldService;
import com.iseonjwa.reservation.seathold.presentation.SeatHoldDtos.AcceptedResponse;
import com.iseonjwa.reservation.seathold.presentation.SeatHoldDtos.HoldRequest;
import com.iseonjwa.reservation.seathold.presentation.SeatHoldDtos.HoldResponse;
import jakarta.validation.Valid;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.UUID;

/**
 * 좌석 선점 API(contracts.md 1·2절). 입장 토큰({@code X-Entry-Token})의 서명·만료·사용자·회차는 Gateway가 검증하므로
 * 여기서 다시 보지 않는다(ISE-20). 사용자 ID는 Gateway가 검증해 전달한 값만 쓴다.
 * 동기형(201)과 접수형(202)은 설정 {@code seat-hold.mode}로 고르고, 분기는 이 API 하나에만 둔다(ADR 0057).
 */
@RestController
public class SeatHoldController {
    // Gateway가 검증한 입장 토큰의 jti를 넣고, 클라이언트가 보낸 같은 헤더는 지운다(contracts.md 1절, ADR 0053).
    // Note: 선택 헤더로 받는다. 배포 경로에서는 Gateway가 이 API 앞에서 입장 토큰을 요구해 늘 붙는다
    static final String ENTRY_TOKEN_ID_HEADER = "X-Entry-Token-Id";

    private final SyncSeatHoldService syncService;
    private final AsyncSeatHoldService asyncService;
    private final SeatHoldMode mode;

    public SeatHoldController(SyncSeatHoldService syncService, AsyncSeatHoldService asyncService,
            @Value("${seat-hold.mode:sync}") String mode) {
        this.syncService = syncService;
        this.asyncService = asyncService;
        this.mode = SeatHoldMode.from(mode);
    }

    @PostMapping("/api/v1/schedules/{scheduleId}/reservations")
    public ResponseEntity<SuccessResponse<?>> hold(@RequestAttribute Long currentUserId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader(value = ENTRY_TOKEN_ID_HEADER, required = false) String entryTokenJti,
            @PathVariable Long scheduleId, @Valid @RequestBody HoldRequest request) {
        if (scheduleId <= 0) {
            throw new BusinessException(CommonErrorCode.VALIDATION_FAILED);
        }
        var command = new HoldSeatsCommand(currentUserId, RequestIds.parse(idempotencyKey), scheduleId,
                request.seatIds(), entryTokenJti);
        if (mode == SeatHoldMode.ASYNC) {
            return accepted(asyncService.accept(command));
        }
        return switch (syncService.hold(command)) {
            case SyncHoldOutcome.Held held -> ResponseEntity.status(201).body(
                    SuccessResponse.of(201, "선점되었습니다", HoldResponse.from(held.result()), MDC.get("traceId")));
            // 접수형으로 시작한 요청은 동기형 서버에서도 202로 답한다(ADR 0058)
            case SyncHoldOutcome.Accepted accepted -> accepted(accepted.requestId());
        };
    }

    // 같은 키 재요청도 같은 202와 본문을 준다. 결과(HELD·REJECTED)는 Location의 조회 API로만 알린다(contracts.md 2-2)
    private static ResponseEntity<SuccessResponse<?>> accepted(UUID requestId) {
        return ResponseEntity.status(202)
                .location(URI.create(SeatHoldRequestController.PATH_PREFIX + requestId))
                .body(SuccessResponse.of(202, "접수되었습니다", AcceptedResponse.of(requestId), MDC.get("traceId")));
    }
}
