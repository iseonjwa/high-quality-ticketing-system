package com.iseonjwa.payment.billing.application;

import com.iseonjwa.common.response.BusinessException;

import java.time.Duration;

/**
 * 등록 횟수 상한을 넘었다. {@code Retry-After} 헤더를 싣기 위해 다시 시도할 수 있을 때까지의 시간을 함께 든다.
 */
public class BillingKeyRateLimitedException extends BusinessException {
    private final Duration retryAfter;

    public BillingKeyRateLimitedException(Duration retryAfter) {
        super(BillingErrorCode.BILLING_KEY_RATE_LIMITED);
        this.retryAfter = retryAfter;
    }

    public Duration retryAfter() {
        return retryAfter;
    }
}
