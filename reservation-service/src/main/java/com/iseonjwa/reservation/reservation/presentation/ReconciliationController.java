package com.iseonjwa.reservation.reservation.presentation;

import com.iseonjwa.common.response.SuccessResponse;
import com.iseonjwa.reservation.reservation.application.ReconciliationQueryService;
import com.iseonjwa.reservation.reservation.presentation.dto.response.ReconciliationChangesResponse;
import com.iseonjwa.reservation.reservation.presentation.dto.response.ReconciliationReservationResponse;
import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/**
 * 결제 서비스의 결제·예매 대사가 부르는 읽기 전용 내부 API(ADR 0029, ISE-75).
 *
 * <p>Note: {@code /internal/**}은 Gateway가 밖에서 막는다(AccessPolicy). 서비스 사이 호출은 내부망을 믿는 모델(ADR 0008)이라
 * 사용자 헤더 없이 부른다.
 */
@RestController
@RequestMapping("/internal/reconciliation/reservations")
@RequiredArgsConstructor
public class ReconciliationController {

    private final ReconciliationQueryService reconciliationQueryService;

    // 예매 ID 목록(최대 100건)의 현재 상태
    @GetMapping
    public ResponseEntity<SuccessResponse<List<ReconciliationReservationResponse>>> getReservations(
            @RequestParam List<Long> ids) {
        var reservations = reconciliationQueryService.findByIds(ids).stream()
                .map(ReconciliationReservationResponse::of)
                .toList();
        return ResponseEntity.ok(SuccessResponse.of(200, "대사용 예매를 조회했습니다", reservations,
                MDC.get("traceId")));
    }

    // 기간 [from, to) 안에 CONFIRMED·CANCELED가 된 예매. 다음 쪽은 응답의 nextCursor를 cursor로 넘긴다
    @GetMapping("/changes")
    public ResponseEntity<SuccessResponse<ReconciliationChangesResponse>> getChanges(@RequestParam Instant from,
            @RequestParam Instant to, @RequestParam(required = false) Long cursor) {
        var reservations = reconciliationQueryService.findSettledChanges(from, to, cursor);
        return ResponseEntity.ok(SuccessResponse.of(200, "대사용 예매 변경을 조회했습니다",
                ReconciliationChangesResponse.of(reservations, ReconciliationQueryService.PAGE_SIZE),
                MDC.get("traceId")));
    }
}
