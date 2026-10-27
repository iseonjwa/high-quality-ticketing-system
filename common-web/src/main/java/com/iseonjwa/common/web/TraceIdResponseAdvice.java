package com.iseonjwa.common.web;

import com.iseonjwa.common.response.SuccessResponse;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/**
 * 컨트롤러가 돌려준 {@link SuccessResponse}에 응답 직전 {@code traceId}를 채운다.
 * 컨트롤러는 추적 ID를 몰라도 된다.
 */
@RestControllerAdvice
public class TraceIdResponseAdvice implements ResponseBodyAdvice<Object> {

    private final TraceIdProvider traceIdProvider;

    public TraceIdResponseAdvice(TraceIdProvider traceIdProvider) {
        this.traceIdProvider = traceIdProvider;
    }

    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        return true;
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType selectedContentType,
                                  Class<? extends HttpMessageConverter<?>> selectedConverterType,
                                  ServerHttpRequest request, ServerHttpResponse response) {
        if (body instanceof SuccessResponse<?> success && success.traceId() == null) {
            return success.withTraceId(traceIdProvider.currentTraceId());
        }
        return body;
    }
}
