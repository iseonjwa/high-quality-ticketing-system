package com.iseonjwa.mockpg.payment;

import java.math.BigDecimal;
import java.time.Instant;

// 승인·조회의 승인 응답 본문(pg-integration.md 6절 프로토콜 표)
record ApprovalResponse(String pgTxId, String status, BigDecimal amount, String currency, Instant approvedAt) {
    static ApprovalResponse of(ApprovedPayment payment) {
        return new ApprovalResponse(payment.pgTxId(), "APPROVED", payment.amount(), payment.currency(),
                payment.approvedAt());
    }
}
