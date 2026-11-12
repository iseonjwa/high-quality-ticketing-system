package com.iseonjwa.reservation.reservation.presentation;

import com.iseonjwa.common.response.BusinessException;
import com.iseonjwa.common.response.CommonErrorCode;
import com.iseonjwa.common.response.SuccessResponse;
import com.iseonjwa.reservation.reservation.application.ReservationDetailResult;
import com.iseonjwa.reservation.reservation.application.ReservationCancelService;
import com.iseonjwa.reservation.reservation.application.ReservationListResult;
import com.iseonjwa.reservation.reservation.application.ReservationService;
import com.iseonjwa.reservation.reservation.presentation.dto.response.ReservationCancelResponse;
import com.iseonjwa.reservation.reservation.presentation.dto.response.ReservationDetailResponse;
import com.iseonjwa.reservation.reservation.presentation.dto.response.ReservationListResponse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.regex.Pattern;

// 로그인한 사용자만 호출한다. 사용자 ID는 Gateway가 검증해 넘긴 값(currentUserId)만 쓴다
@RestController
@RequestMapping("/api/v1/reservations")
@RequiredArgsConstructor
public class ReservationController {

    // UUID.fromString은 "1-1-1-1-1"처럼 자리수가 모자란 값도 받아들여서 표준 형식(8-4-4-4-12)을 먼저 확인한다
    private static final Pattern UUID_FORMAT = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private final ReservationService reservationService;
    private final ReservationCancelService reservationCancelService;

    // 내 예매 목록. 경로 "/me"가 "/{reservationId}"보다 구체적이라 먼저 맞는다
    @GetMapping("/me")
    public ResponseEntity<SuccessResponse<ReservationListResponse>> getMyReservations(
            @RequestAttribute Long currentUserId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        ReservationListResult result = reservationService.getMyReservations(currentUserId, page, size);
        return ResponseEntity.ok(SuccessResponse.of(200, "내 예매 목록을 조회했습니다",
                ReservationListResponse.from(result), MDC.get("traceId")));
    }

    // 예매 상세 조회
    @GetMapping("/{reservationId}")
    public ResponseEntity<SuccessResponse<ReservationDetailResponse>> getReservation(
            @RequestAttribute Long currentUserId,
            @PathVariable(name = "reservationId") Long reservationId) {
        if (reservationId <= 0) {
            throw new BusinessException(CommonErrorCode.VALIDATION_FAILED);
        }
        ReservationDetailResult result = reservationService.getReservation(currentUserId, reservationId);
        return ResponseEntity.ok(SuccessResponse.of(200, "예매를 조회했습니다",
                ReservationDetailResponse.from(result), MDC.get("traceId")));
    }

    // 예매 취소. 키는 형식만 확인하고 저장하지 않는다. 멱등은 예매 상태로 지킨다(ReservationCancelService)
    @PostMapping("/{reservationId}/cancel")
    public ResponseEntity<SuccessResponse<ReservationCancelResponse>> cancelReservation(
            @RequestAttribute Long currentUserId,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @PathVariable(name = "reservationId") Long reservationId) {
        if (reservationId <= 0 || idempotencyKey == null || !UUID_FORMAT.matcher(idempotencyKey).matches()) {
            throw new BusinessException(CommonErrorCode.VALIDATION_FAILED);
        }
        var result = reservationCancelService.cancel(currentUserId, reservationId);
        return ResponseEntity.status(202).body(SuccessResponse.of(202, "예매를 취소했습니다",
                ReservationCancelResponse.from(result), MDC.get("traceId")));
    }
}
