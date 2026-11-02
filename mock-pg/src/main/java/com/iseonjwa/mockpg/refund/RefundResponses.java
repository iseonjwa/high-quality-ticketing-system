package com.iseonjwa.mockpg.refund;

import com.iseonjwa.mockpg.ledger.RefundOutcome;
import com.iseonjwa.mockpg.ledger.RefundOutcome.Refunded;
import com.iseonjwa.mockpg.ledger.RefundOutcome.Rejected;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 환불 결과를 Mock PG 프로토콜 응답으로 바꾼다(pg-integration.md 6절, mock-pg.md 11절). 어댑터는 본문의 status·code로
 * 결과를 분류하므로 본문은 서버가 항상 직접 만든다. 금액은 숫자, 시각은 ISO-8601 문자열이다.
 */
final class RefundResponses {
    private RefundResponses() {
    }

    static ResponseEntity<?> refund(RefundOutcome outcome) {
        return switch (outcome) {
            case Refunded refunded -> ResponseEntity.ok(RefundedBody.of(refunded));
            case Rejected rejected -> ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                    .body(new RejectedBody("REJECTED", rejected.code().name()));
        };
    }

    record RefundedBody(String refundTxId, String pgTxId, String status, BigDecimal amount, String currency,
            Instant refundedAt) {
        static RefundedBody of(Refunded refunded) {
            return new RefundedBody(refunded.refundTxId(), refunded.pgTxId(), "REFUNDED", refunded.amount(),
                    refunded.currency(), refunded.refundedAt());
        }
    }

    record RejectedBody(String status, String code) {
    }
}
