package com.iseonjwa.mockpg.payment;

import java.math.BigDecimal;

/**
 * 승인 요청 본문. 형식이 틀리면 만들 때 실패해서 400 {@code INVALID_REQUEST}가 된다({@code InvalidRequestHandler}).
 */
record ApproveRequest(String orderId, BigDecimal amount, String currency) {
    ApproveRequest {
        if (orderId == null || orderId.isBlank()) {
            throw new IllegalArgumentException("orderId가 필요합니다");
        }
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("amount는 0보다 커야 합니다");
        }
        if (currency == null || currency.isBlank()) {
            throw new IllegalArgumentException("currency가 필요합니다");
        }
    }
}
