package com.iseonjwa.payment.billing.presentation;

import com.iseonjwa.common.response.FailureResponse;
import com.iseonjwa.payment.billing.application.BillingKeyRateLimitedException;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 등록 횟수 상한 초과를 공통 업무 예외와 같은 본문으로 답하되 {@code Retry-After}(초, 올림)를 더한다. {@code common}의 처리기를 고치지 않으려고 따로 둔다.
 */
@RestControllerAdvice(assignableTypes = BillingKeyController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
class BillingKeyRateLimitedHandler {
    @ExceptionHandler(BillingKeyRateLimitedException.class)
    ResponseEntity<FailureResponse> handle(BillingKeyRateLimitedException exception) {
        var error = exception.getErrorCode();
        var seconds = Math.max(1, (exception.retryAfter().toMillis() + 999) / 1000);
        return ResponseEntity.status(error.status())
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.RETRY_AFTER, Long.toString(seconds))
                .body(FailureResponse.of(error, null, MDC.get("traceId")));
    }
}
