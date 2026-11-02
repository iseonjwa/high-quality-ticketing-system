package com.iseonjwa.mockpg.payment;

import com.iseonjwa.mockpg.web.ErrorResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;

/**
 * Mock PG 승인·조회 API(pg-integration.md 6절 프로토콜 표).
 */
@RestController
@RequestMapping("/mock-pg/payments")
class PaymentController {
    private final PaymentLedger ledger;

    PaymentController(PaymentLedger ledger) {
        this.ledger = ledger;
    }

    @PostMapping
    ResponseEntity<ApprovalResponse> approve(@RequestBody ApproveRequest request) {
        var payment = ledger.approve(request.orderId(), request.amount(), request.currency());
        return ResponseEntity.ok(ApprovalResponse.of(payment));
    }

    @GetMapping("/{orderId}")
    ResponseEntity<?> find(@PathVariable String orderId) {
        var payment = ledger.find(orderId);
        if (payment.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ErrorResponse.NOT_FOUND);
        }
        return ResponseEntity.ok(ApprovalResponse.of(payment.get()));
    }

    record ApproveRequest(String orderId, BigDecimal amount, String currency) {
    }
}
