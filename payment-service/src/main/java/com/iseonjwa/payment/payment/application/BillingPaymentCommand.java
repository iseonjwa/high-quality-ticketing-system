package com.iseonjwa.payment.payment.application;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * 추첨 당첨 자동결제 요청({@code BillingPaymentRequested}, ISE-92·96). 금액은 예매 원장 값이고 결과는 예매가 원장과 대조한다(0027).
 */
public record BillingPaymentCommand(long reservationId, long userId, BigDecimal amount, String currency) {
    public BillingPaymentCommand {
        Objects.requireNonNull(amount);
        if (amount.signum() <= 0) throw new IllegalArgumentException("결제 금액은 0보다 커야 합니다");
        if (!"KRW".equals(currency)) throw new IllegalArgumentException("통화는 KRW만 받는다");
    }
}
