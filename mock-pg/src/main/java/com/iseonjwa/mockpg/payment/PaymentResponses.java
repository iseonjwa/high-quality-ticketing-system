package com.iseonjwa.mockpg.payment;

import com.iseonjwa.mockpg.ledger.OrderState;
import com.iseonjwa.mockpg.ledger.OrderState.Approved;
import com.iseonjwa.mockpg.ledger.OrderState.Canceled;
import com.iseonjwa.mockpg.ledger.OrderState.Declined;
import com.iseonjwa.mockpg.ledger.OrderState.InFlight;
import com.iseonjwa.mockpg.web.ErrorResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

/**
 * 원장 상태를 Mock PG 프로토콜 응답으로 바꾼다. 세 메서드가 mock-pg.md 4절 상태 표의 승인·망취소·조회 열이다.
 * 어댑터는 본문의 status·code로 결과를 분류하므로 본문은 서버가 항상 직접 만든다(11절).
 */
final class PaymentResponses {
    private PaymentResponses() {
    }

    static ResponseEntity<?> approval(OrderState state) {
        return switch (state) {
            // 같은 주문의 다른 시도가 처리 중이다. 토스가 처리 중인 같은 키 요청에 409를 준다고 알려진 동작을 닮게 했다(7절 #3)
            case InFlight inFlight -> status(HttpStatus.CONFLICT, ErrorResponse.REQUEST_IN_PROGRESS);
            case Approved approved -> ResponseEntity.ok(ApprovedBody.of(approved));
            case Declined declined -> ResponseEntity.ok(DeclinedBody.of(declined));
            case Canceled canceled -> status(HttpStatus.CONFLICT, ErrorResponse.ORDER_CANCELED);
        };
    }

    static ResponseEntity<?> cancel(OrderState state) {
        return switch (state) {
            // 처리 중이면 취소를 보장할 수 없다. CANCELED를 주면 어댑터가 Prevented로 단정한다(INV-8)
            case InFlight inFlight -> processing();
            case Approved approved -> ResponseEntity.ok(ApprovedBody.of(approved));
            // 거절은 그대로 두고 취소됐다고만 답한다. 거절 본문을 주면 어댑터가 해석하지 못해 PG_ERROR로 본다
            case Declined declined -> ResponseEntity.ok(StatusBody.CANCELED);
            case Canceled canceled -> ResponseEntity.ok(StatusBody.CANCELED);
        };
    }

    static ResponseEntity<?> lookup(Optional<OrderState> found) {
        if (found.isEmpty()) return notFound();
        return switch (found.get()) {
            case InFlight inFlight -> processing();
            case Approved approved -> ResponseEntity.ok(ApprovedBody.of(approved));
            case Declined declined -> ResponseEntity.ok(DeclinedBody.of(declined));
            // 망취소된 주문은 승인된 적이 없으므로 조회에서는 없는 주문과 같다
            case Canceled canceled -> notFound();
        };
    }

    private static ResponseEntity<?> processing() {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(StatusBody.PROCESSING);
    }

    private static ResponseEntity<?> notFound() {
        return status(HttpStatus.NOT_FOUND, ErrorResponse.NOT_FOUND);
    }

    private static ResponseEntity<?> status(HttpStatus status, ErrorResponse body) {
        return ResponseEntity.status(status).body(body);
    }

    record ApprovedBody(String pgTxId, String status, BigDecimal amount, String currency, Instant approvedAt) {
        static ApprovedBody of(Approved approved) {
            return new ApprovedBody(approved.pgTxId(), "APPROVED", approved.amount(), approved.currency(),
                    approved.approvedAt());
        }
    }

    record DeclinedBody(String status, String declineCode) {
        static DeclinedBody of(Declined declined) {
            return new DeclinedBody("DECLINED", declined.declineCode());
        }
    }

    record StatusBody(String status) {
        static final StatusBody CANCELED = new StatusBody("CANCELED");
        static final StatusBody PROCESSING = new StatusBody("PROCESSING");
    }
}
