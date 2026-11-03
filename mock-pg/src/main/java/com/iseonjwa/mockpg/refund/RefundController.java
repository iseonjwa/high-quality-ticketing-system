package com.iseonjwa.mockpg.refund;

import com.iseonjwa.mockpg.web.ErrorResponse;
import com.iseonjwa.mockpg.web.FaultResponses;
import com.iseonjwa.mockpg.web.ResponseDrop;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Mock PG 환불·환불 조회 API(pg-integration.md 6절 프로토콜 표). 거절된 환불은 저장하지 않으므로 그 환불 식별자의 조회는 404다.
 * 장애 응답을 먼저, 그다음 원장 판정 응답을 쓴다.
 */
@RestController
@RequestMapping("/mock-pg/refunds")
class RefundController {
    private final RefundService refunds;

    RefundController(RefundService refunds) {
        this.refunds = refunds;
    }

    @PostMapping
    ResponseEntity<?> refund(@RequestBody RefundRequest request,
                             @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                             HttpServletRequest servletRequest) {
        var result = refunds.refund(request, idempotencyKey);
        var faultResponse = FaultResponses.of(result.fault());
        if (faultResponse.isPresent()) return faultResponse.get();
        if (result.dropsResponse()) {
            // Note: 원장 처리를 끝낸 뒤 응답만 잃는다(저장 뒤 유실). 아무것도 쓰지 않고 Valve가 연결을 닫게 한다
            ResponseDrop.request(servletRequest);
            return null;
        }
        return RefundResponses.refund(result.outcome());
    }

    @GetMapping("/{refundId}")
    ResponseEntity<?> find(@PathVariable String refundId) {
        var result = refunds.find(refundId);
        var faultResponse = FaultResponses.of(result.fault());
        if (faultResponse.isPresent()) return faultResponse.get();
        return result.refunded()
                .<ResponseEntity<?>>map(refunded -> ResponseEntity.ok(RefundResponses.RefundedBody.of(refunded)))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(ErrorResponse.NOT_FOUND));
    }
}
