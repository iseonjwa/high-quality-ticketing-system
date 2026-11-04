package com.iseonjwa.payment.payment.application;

import java.util.UUID;

/**
 * 결제 승인 요청. {@code userId}는 Gateway가 검증해 전달한 값만 쓴다(0021).
 */
public record ApprovePaymentCommand(long userId, UUID idempotencyKey, String paymentToken, long amount) {}
