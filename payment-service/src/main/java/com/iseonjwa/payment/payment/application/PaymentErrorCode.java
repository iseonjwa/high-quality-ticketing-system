package com.iseonjwa.payment.payment.application;

import com.iseonjwa.common.response.ErrorCode;

/**
 * 결제 도메인 에러 코드(api.md 3절). 공통 코드(VALIDATION_FAILED, IDEMPOTENCY_KEY_REUSED, NOT_FOUND,
 * SERVICE_UNAVAILABLE)는 {@code CommonErrorCode}를 쓴다.
 */
public enum PaymentErrorCode implements ErrorCode {
    PAYMENT_TOKEN_INVALID(403, "결제 토큰이 올바르지 않습니다"),
    PAYMENT_AMOUNT_MISMATCH(400, "결제 금액이 토큰의 금액과 다릅니다"),
    PAYMENT_ALREADY_EXISTS(409, "이미 처리 중이거나 확인이 필요한 결제가 있습니다"),
    PAYMENT_ALREADY_CLOSED(409, "이 예매의 결제는 종료됐습니다. 새로 선점해 다시 시도하세요"),
    PG_UNAVAILABLE(503, "결제 대행사를 일시적으로 사용할 수 없습니다");

    private final int status;
    private final String message;

    PaymentErrorCode(int status, String message) {
        this.status = status;
        this.message = message;
    }

    @Override
    public int status() {
        return status;
    }

    @Override
    public String code() {
        return name();
    }

    @Override
    public String message() {
        return message;
    }
}
