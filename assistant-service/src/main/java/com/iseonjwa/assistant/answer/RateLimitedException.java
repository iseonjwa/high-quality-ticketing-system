package com.iseonjwa.assistant.answer;

import com.iseonjwa.common.response.BusinessException;
import com.iseonjwa.common.response.CommonErrorCode;

import java.time.Duration;

/** 사용자당 요청 상한을 넘었다. 429로 내보내며 {@code Retry-After}에 기다릴 초를 싣는다. */
public class RateLimitedException extends BusinessException {
    private final long retryAfterSeconds;

    public RateLimitedException(Duration wait) {
        super(CommonErrorCode.RATE_LIMITED);
        this.retryAfterSeconds = Math.max(1, (wait.toMillis() + 999) / 1000);
    }

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
