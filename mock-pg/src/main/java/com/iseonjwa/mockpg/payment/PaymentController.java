package com.iseonjwa.mockpg.payment;

import com.iseonjwa.mockpg.web.ResponseDrop;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Mock PG 승인·망취소·조회 API(pg-integration.md 6절 프로토콜 표). 상태별 응답은 {@link PaymentResponses}에 있다.
 */
@RestController
@RequestMapping("/mock-pg/payments")
class PaymentController {
    private final PaymentService payments;

    PaymentController(PaymentService payments) {
        this.payments = payments;
    }

    @PostMapping
    ResponseEntity<?> approve(@RequestBody ApproveRequest request, HttpServletRequest servletRequest) {
        var approval = payments.approve(request);
        if (approval.dropsResponse()) {
            // Note: 저장을 끝낸 뒤 응답만 잃는다. ResponseEntity를 돌려주면 메시지 컨버터가 상태 줄과 헤더를
            // flush해 이미 클라이언트로 나가므로, null을 돌려줘 아무것도 쓰지 않고 Valve가 연결을 닫게 한다
            ResponseDrop.request(servletRequest);
            return null;
        }
        return PaymentResponses.approval(approval.state());
    }

    @PostMapping("/{orderId}/cancel")
    ResponseEntity<?> cancel(@PathVariable String orderId) {
        return PaymentResponses.cancel(payments.cancel(orderId));
    }

    @GetMapping("/{orderId}")
    ResponseEntity<?> find(@PathVariable String orderId) {
        return PaymentResponses.lookup(payments.find(orderId));
    }
}
