package com.iseonjwa.mockpg.payment;

import com.iseonjwa.mockpg.ledger.OrderState;
import com.iseonjwa.mockpg.web.FaultResponses;
import com.iseonjwa.mockpg.web.ResponseDrop;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;
import java.util.function.Function;

/**
 * Mock PG 승인·망취소·조회 API(pg-integration.md 6절 프로토콜 표). 응답은 장애 응답을 먼저, 그다음 원장 상태 응답을
 * 쓴다({@link PaymentResponses}).
 */
@RestController
@RequestMapping("/mock-pg/payments")
class PaymentController {
    private static final String IDEMPOTENCY_KEY = "Idempotency-Key";

    private final PaymentService payments;

    PaymentController(PaymentService payments) {
        this.payments = payments;
    }

    @PostMapping
    ResponseEntity<?> approve(@RequestBody ApproveRequest request,
                              @RequestHeader(value = IDEMPOTENCY_KEY, required = false) String idempotencyKey,
                              HttpServletRequest servletRequest) {
        return respond(payments.approve(request, idempotencyKey), servletRequest,
                state -> PaymentResponses.approval(state.orElseThrow()));
    }

    @PostMapping("/{orderId}/cancel")
    ResponseEntity<?> cancel(@PathVariable String orderId, HttpServletRequest servletRequest) {
        return respond(payments.cancel(orderId), servletRequest,
                state -> PaymentResponses.cancel(state.orElseThrow()));
    }

    @GetMapping("/{orderId}")
    ResponseEntity<?> find(@PathVariable String orderId, HttpServletRequest servletRequest) {
        return respond(payments.find(orderId), servletRequest, PaymentResponses::lookup);
    }

    private ResponseEntity<?> respond(PaymentService.Outcome outcome, HttpServletRequest servletRequest,
                                      Function<Optional<OrderState>, ResponseEntity<?>> ledgerResponse) {
        var faultResponse = FaultResponses.of(outcome.fault());
        if (faultResponse.isPresent()) return faultResponse.get();
        if (outcome.dropsResponse()) {
            // Note: 원장 처리를 끝낸 뒤 응답만 잃는다. 새 주문이면 결과를 저장한 뒤고, 망취소면 반영한 뒤다.
            // ResponseEntity를 돌려주면 메시지 컨버터가 상태 줄과 헤더를 flush해 이미 클라이언트로 나가므로,
            // null을 돌려줘 아무것도 쓰지 않고 Valve가 연결을 닫게 한다
            ResponseDrop.request(servletRequest);
            return null;
        }
        return ledgerResponse.apply(outcome.state());
    }
}
