package com.iseonjwa.mockpg.ledger;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * 환불 요청의 판정 결과(mock-pg.md 8절). 성공만 원장에 저장하고 거절은 저장하지 않는다.
 */
public sealed interface RefundOutcome permits RefundOutcome.Refunded, RefundOutcome.Rejected {

    /**
     * 저장되는 환불 결과. 같은 {@code refundId}의 재요청에는 이 결과를 그대로 돌려준다(멱등).
     */
    record Refunded(String refundId, String refundTxId, String pgTxId, BigDecimal amount, String currency,
            Instant refundedAt) implements RefundOutcome {
        public Refunded {
            Objects.requireNonNull(refundId);
            Objects.requireNonNull(refundTxId);
            Objects.requireNonNull(pgTxId);
            Objects.requireNonNull(amount);
            Objects.requireNonNull(currency);
            Objects.requireNonNull(refundedAt);
        }
    }

    record Rejected(RejectCode code) implements RefundOutcome {
        public Rejected {
            Objects.requireNonNull(code);
        }
    }

    enum RejectCode {
        // 승인된 결제가 없거나 통화가 다르거나 환불 합계가 승인 금액을 넘는다
        NOT_REFUNDABLE,
        // 같은 refundId가 다른 결제로 이미 쓰였다
        OTHER
    }
}
