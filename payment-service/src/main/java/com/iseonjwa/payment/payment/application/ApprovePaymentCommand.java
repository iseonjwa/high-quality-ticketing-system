package com.iseonjwa.payment.payment.application;

import java.util.UUID;

/**
 * 결제 승인 요청. {@code userId}는 Gateway가 검증해 전달한 값만 쓴다(0021).
 *
 * @param clientPaymentRef 구매자가 결제창에서 받은 PG 발급 식별자(토스 paymentKey). 토스 모드에서 필수이고 Mock 모드에서는 무시한다.
 */
public record ApprovePaymentCommand(long userId, UUID idempotencyKey, String paymentToken, long amount,
        String clientPaymentRef) {
    public ApprovePaymentCommand(long userId, UUID idempotencyKey, String paymentToken, long amount) {
        this(userId, idempotencyKey, paymentToken, amount, null);
    }
}
