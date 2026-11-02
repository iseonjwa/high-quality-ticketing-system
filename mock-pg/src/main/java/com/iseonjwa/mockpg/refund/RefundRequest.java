package com.iseonjwa.mockpg.refund;

import java.math.BigDecimal;

/**
 * 환불 요청 본문. 형식이 틀리면 만들 때 실패해서 400 {@code INVALID_REQUEST}가 된다({@code InvalidRequestHandler}).
 * {@code reason}은 선택이다.
 */
record RefundRequest(String pgTxId, String refundId, BigDecimal amount, String currency, String reason) {
    RefundRequest {
        if (pgTxId == null || pgTxId.isBlank()) {
            throw new IllegalArgumentException("pgTxId가 필요합니다");
        }
        if (refundId == null || refundId.isBlank()) {
            throw new IllegalArgumentException("refundId가 필요합니다");
        }
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("amount는 0보다 커야 합니다");
        }
        if (currency == null || currency.isBlank()) {
            throw new IllegalArgumentException("currency가 필요합니다");
        }
    }
}
