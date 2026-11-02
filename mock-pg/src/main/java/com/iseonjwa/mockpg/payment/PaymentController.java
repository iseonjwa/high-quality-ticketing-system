package com.iseonjwa.mockpg.payment;

import com.iseonjwa.mockpg.fault.FaultInjector;
import com.iseonjwa.mockpg.fault.FaultMode;
import com.iseonjwa.mockpg.fault.FaultTarget;
import com.iseonjwa.mockpg.web.ErrorResponse;
import com.iseonjwa.mockpg.web.ResponseDrop;
import jakarta.servlet.http.HttpServletRequest;
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
    private final FaultInjector faults;

    PaymentController(PaymentLedger ledger, FaultInjector faults) {
        this.ledger = ledger;
        this.faults = faults;
    }

    @PostMapping
    ResponseEntity<ApprovalResponse> approve(@RequestBody ApproveRequest request, HttpServletRequest servletRequest) {
        var fault = faults.claim(FaultTarget.APPROVE);
        var payment = ledger.approve(request.orderId(), request.amount(), request.currency());
        if (fault == FaultMode.DROP_RESPONSE) {
            // Note: 저장을 끝낸 뒤 응답만 잃는다. ResponseEntity를 돌려주면 메시지 컨버터가 상태 줄과 헤더를
            // flush해 이미 클라이언트로 나가므로, null을 돌려줘 아무것도 쓰지 않고 Valve가 연결을 닫게 한다
            ResponseDrop.request(servletRequest);
            return null;
        }
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
