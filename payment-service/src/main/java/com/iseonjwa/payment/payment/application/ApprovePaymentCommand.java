package com.iseonjwa.payment.payment.application;

import com.iseonjwa.payment.payment.domain.PgProvider;

import java.util.UUID;

/**
 * 결제 승인 요청. {@code userId}는 Gateway가 검증해 전달한 값만 쓴다(0021).
 *
 * @param clientPaymentRef 구매자가 결제창에서 받은 PG 발급 식별자(토스 paymentKey). 토스로 처리할 때 필수이고 Mock으로 처리하면 무시한다.
 * @param pgProvider 이 결제를 처리할 PG. null이면 서버 기본 PG다. 토스 모드에서 MOCK이면 결제창을 건너뛴다(시연용).
 */
public record ApprovePaymentCommand(long userId, UUID idempotencyKey, String paymentToken, long amount,
        String clientPaymentRef, PgProvider pgProvider) {
    public ApprovePaymentCommand(long userId, UUID idempotencyKey, String paymentToken, long amount,
            String clientPaymentRef) {
        this(userId, idempotencyKey, paymentToken, amount, clientPaymentRef, null);
    }

    public ApprovePaymentCommand(long userId, UUID idempotencyKey, String paymentToken, long amount) {
        this(userId, idempotencyKey, paymentToken, amount, null, null);
    }
}
