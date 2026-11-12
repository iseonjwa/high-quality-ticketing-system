package com.iseonjwa.reservation.seathold.presentation;

import com.iseonjwa.common.response.SuccessResponse;
import com.iseonjwa.reservation.seathold.application.SeatHoldRequestQueryService;
import com.iseonjwa.reservation.seathold.presentation.SeatHoldDtos.RequestResultResponse;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RestController;

/**
 * 선점 접수 결과 조회(contracts.md 2-2절). 모드와 상관없이 연다(ADR 0054). 입장 토큰 검사 대상이 아니어서
 * 입장 토큰이 만료된 뒤에도 결과를 볼 수 있다(Gateway {@code EntryTokenPolicy}는 선점 POST만 본다).
 */
@RestController
public class SeatHoldRequestController {
    static final String PATH_PREFIX = "/api/v1/reservations/requests/";

    private final SeatHoldRequestQueryService queryService;

    public SeatHoldRequestController(SeatHoldRequestQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping(PATH_PREFIX + "{requestId}")
    public ResponseEntity<SuccessResponse<RequestResultResponse>> find(@RequestAttribute Long currentUserId,
            @PathVariable String requestId) {
        var result = queryService.find(RequestIds.parse(requestId), currentUserId);
        return ResponseEntity.ok(SuccessResponse.of(200, "조회했습니다", RequestResultResponse.from(result),
                MDC.get("traceId")));
    }
}
