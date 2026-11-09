package com.iseonjwa.reservation.seathold.presentation;

import com.iseonjwa.common.response.SuccessResponse;
import com.iseonjwa.reservation.seathold.application.SeatLedgerReconciliationService;
import com.iseonjwa.reservation.seathold.application.SeatLedgerReconciliationService.LedgerInspection;
import com.iseonjwa.reservation.seathold.application.SeatLedgerReconciliationService.LedgerRepair;
import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 회차 좌석·보유 매수 대사(ISE-14). 부하·장애 주입 테스트 뒤 운영자나 측정 도구가 내부망에서 부른다.
 *
 * <p>Note: {@code /internal/**}은 Gateway가 밖에서 막는다(AccessPolicy). 다른 서비스가 부르지 않는 운영용 API라
 * 서비스 간 계약이 아니다.
 */
@RestController
@RequestMapping("/internal/seat-holds/schedules/{scheduleId}/ledger")
@RequiredArgsConstructor
public class SeatLedgerReconciliationController {

    private final SeatLedgerReconciliationService reconciliation;

    // 고치지 않고 어긋난 좌석·보유 매수를 본다
    @GetMapping
    public ResponseEntity<SuccessResponse<LedgerInspection>> inspect(@PathVariable Long scheduleId) {
        return ResponseEntity.ok(SuccessResponse.of(200, "좌석 원장 대사를 조회했습니다",
                reconciliation.inspect(scheduleId), MDC.get("traceId")));
    }

    // 원장 기준으로 고친다. remaining이 비지 않았으면 다시 부른다
    @PostMapping("/reconcile")
    public ResponseEntity<SuccessResponse<LedgerRepair>> reconcile(@PathVariable Long scheduleId) {
        return ResponseEntity.ok(SuccessResponse.of(200, "좌석 원장 대사를 실행했습니다",
                reconciliation.reconcile(scheduleId), MDC.get("traceId")));
    }
}
