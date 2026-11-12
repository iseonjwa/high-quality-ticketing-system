package com.iseonjwa.payment.billing.application;

import com.iseonjwa.common.response.ErrorCode;

public enum BillingErrorCode implements ErrorCode {
    BILLING_KEY_REJECTED(400, "카드 등록 인증 값이 올바르지 않습니다"),
    BILLING_KEY_RATE_LIMITED(429, "카드 등록을 너무 자주 시도했습니다. 잠시 뒤 다시 시도해 주세요");

    private final int status;
    private final String message;

    BillingErrorCode(int status, String message) {
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
