package com.iseonjwa.mockpg.refund;

import com.iseonjwa.mockpg.web.ErrorResponse;
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
                             @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return RefundResponses.refund(refunds.refund(request, idempotencyKey));
    }

    @GetMapping("/{refundId}")
    ResponseEntity<?> find(@PathVariable String refundId) {
        return refunds.find(refundId)
                .<ResponseEntity<?>>map(refunded -> ResponseEntity.ok(RefundResponses.RefundedBody.of(refunded)))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(ErrorResponse.NOT_FOUND));
    }
}
