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
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;

@RestController
@RequestMapping("/api/v1/assistant")
class AssistantController {
    // Note: 전체 시한(20초)보다 길게 둬서 서비스가 먼저 done으로 끝내게 한다. 이 값은 응답이 멈췄을 때의 안전망이다
    private static final long STREAM_TIMEOUT_MILLIS = 30_000;

    private final AssistantService service;
    private final JsonMapper json;

    AssistantController(AssistantService service, JsonMapper json) {
        this.service = service;
        this.json = json;
    }

    /**
     * 스트리밍 답변(SSE). 검증(400)·인증(401)·요청 제한(429)은 SSE를 열기 전에 JSON 오류로 끝난다.
     * 통과하면 가상 스레드에서 이벤트를 흘려보낸다. 연결이 끊기면 다음 이벤트를 쓰다 알아채고 Claude 스트림을 닫는다.
     */
    @PostMapping("/messages/stream")
    ResponseEntity<SseEmitter> stream(@RequestAttribute(GatewayIdentityFilter.USER_ID_ATTRIBUTE) Long userId,
            @Valid @RequestBody AskRequest request) {
        var streaming = service.prepareStream(userId, request.question());
        var emitter = new SseEmitter(STREAM_TIMEOUT_MILLIS);
        var sink = new SseStreamSink(emitter, json, MDC.get("traceId"));
        // Note: 가상 스레드에는 요청 스레드의 MDC가 따라오지 않는다. 복사해 넣어 스트림 로그에도 traceId를 남긴다
        var requestMdc = MDC.getCopyOfContextMap();
        Thread.ofVirtual().name("assistant-stream").start(() -> {
            if (requestMdc != null) {
                MDC.setContextMap(requestMdc);
            }
            try {
                streaming.run(sink);
                emitter.complete();
            } catch (RuntimeException exception) {
                emitter.completeWithError(exception);
            } finally {
                MDC.clear();
            }
        });
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "event-stream", StandardCharsets.UTF_8))
                .cacheControl(CacheControl.noCache())
                // Note: nginx가 응답을 모아 두지 않고 바로 흘려보내게 한다(프론트 nginx 앞단)
                .header("X-Accel-Buffering", "no")
                .body(emitter);
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
                .contentType(MediaType.APPLICATION_JSON)
                .body(FailureResponse.of(CommonErrorCode.RATE_LIMITED, null, MDC.get("traceId")));
    }
}
