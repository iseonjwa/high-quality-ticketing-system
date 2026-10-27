package com.iseonjwa.gateway.error;

import com.iseonjwa.common.response.BusinessException;
import com.iseonjwa.common.response.CommonErrorCode;
import com.iseonjwa.common.response.ErrorResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.webflux.error.ErrorWebExceptionHandler;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.http.codec.HttpMessageWriter;
import org.springframework.http.codec.ServerCodecConfigurer;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.reactive.result.view.ViewResolver;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.ConnectException;
import java.util.List;

/**
 * Gateway에서 끝나는 모든 실패를 실패 응답 형식으로 만든다 (SA 11-2).
 * 인증 실패(401), 라우트 없음(404), 서비스 인스턴스 없음(503), 서비스 응답 시간 초과(504)가 여기로 온다.
 * Boot 기본 처리기(순서 -1)보다 먼저 실행한다.
 */
@Component
@Order(-2)
public class GatewayErrorHandler implements ErrorWebExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GatewayErrorHandler.class);

    private final TraceIdResolver traceIdResolver;
    private final ServerResponse.Context responseContext;

    public GatewayErrorHandler(TraceIdResolver traceIdResolver, ServerCodecConfigurer codecConfigurer) {
        this.traceIdResolver = traceIdResolver;
        List<HttpMessageWriter<?>> writers = codecConfigurer.getWriters();
        this.responseContext = new ServerResponse.Context() {
            @Override
            public List<HttpMessageWriter<?>> messageWriters() {
                return writers;
            }

            @Override
            public List<ViewResolver> viewResolvers() {
                return List.of();
            }
        };
    }

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable ex) {
        if (exchange.getResponse().isCommitted()) {
            return Mono.error(ex);
        }
        ErrorResponse body = toErrorResponse(ex, traceIdResolver.resolve(exchange));
        return ServerResponse.status(body.status())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .flatMap(response -> response.writeTo(exchange, responseContext));
    }

    private ErrorResponse toErrorResponse(Throwable ex, String traceId) {
        if (ex instanceof BusinessException e) {
            return ErrorResponse.of(e.getErrorCode(), e.getMessage(), traceId);
        }
        if (ex instanceof ResponseStatusException e) {
            int status = e.getStatusCode().value();
            if (status >= 500) {
                log.warn("라우팅 실패 {}: {}", status, e.getReason());
            }
            return ErrorResponse.ofStatus(status, traceId);
        }
        if (ex instanceof ConnectException) {
            log.warn("서비스 연결 실패: {}", ex.getMessage());
            return ErrorResponse.of(CommonErrorCode.SERVICE_UNAVAILABLE, traceId);
        }
        log.error("처리하지 못한 예외", ex);
        return ErrorResponse.of(CommonErrorCode.INTERNAL_ERROR, traceId);
    }
}
