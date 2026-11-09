package com.iseonjwa.assistant.web;

import com.iseonjwa.assistant.answer.Answer;
import com.iseonjwa.assistant.answer.AssistantService;
import com.iseonjwa.assistant.answer.RateLimitedException;
import com.iseonjwa.common.response.CommonErrorCode;
import com.iseonjwa.common.response.FailureResponse;
import com.iseonjwa.common.response.SuccessResponse;
import com.iseonjwa.common.web.GatewayIdentityFilter;
import jakarta.validation.Valid;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/assistant")
class AssistantController {
    private final AssistantService service;

    AssistantController(AssistantService service) {
        this.service = service;
    }

    @PostMapping("/messages")
    SuccessResponse<Answer> ask(@RequestAttribute(GatewayIdentityFilter.USER_ID_ATTRIBUTE) Long userId,
            @Valid @RequestBody AskRequest request) {
        return SuccessResponse.of(200, "OK", service.ask(userId, request.question()), MDC.get("traceId"));
    }

    // Note: 공통 예외 처리기는 BusinessException을 본문만으로 내보낸다. Retry-After 헤더가 필요해 이 컨트롤러에서 직접 만든다
    @ExceptionHandler(RateLimitedException.class)
    ResponseEntity<FailureResponse> rateLimited(RateLimitedException exception) {
        return ResponseEntity.status(CommonErrorCode.RATE_LIMITED.status())
                .header(HttpHeaders.RETRY_AFTER, Long.toString(exception.retryAfterSeconds()))
                .body(FailureResponse.of(CommonErrorCode.RATE_LIMITED, null, MDC.get("traceId")));
    }
}
