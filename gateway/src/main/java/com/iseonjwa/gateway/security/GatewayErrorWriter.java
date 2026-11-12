package com.iseonjwa.gateway.security;

import io.micrometer.tracing.Tracer;
import com.iseonjwa.common.response.FailureResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;

@Component
public class GatewayErrorWriter {
    private static final Duration DEFAULT_RETRY_AFTER = Duration.ofSeconds(1);
    private final ObjectMapper mapper;
    private final Tracer tracer;

    public GatewayErrorWriter(ObjectMapper mapper, Tracer tracer) {
        this.mapper = mapper;
        this.tracer = tracer;
    }

    public Mono<Void> write(ServerWebExchange exchange, int status, String code, String message) {
        return write(exchange, status, code, message, status == 503 ? DEFAULT_RETRY_AFTER : null);
    }

    /** 원인마다 다시 시도할 간격이 다를 때 쓴다. {@code retryAfter}가 null이면 Retry-After를 넣지 않는다. */
    public Mono<Void> write(ServerWebExchange exchange, int status, String code, String message, Duration retryAfter) {
        return write(exchange.getResponse(), status, code, message, retryAfter);
    }

    /**
     * 요청 객체(exchange)가 만들어지기 전 단계(HttpHandler)에서 응답을 쓸 때 쓴다.
     * 그 단계에는 서버 관측이 아직 열리지 않아 현재 스팬이 없으므로 본문의 traceId는 null이다.
     */
    public Mono<Void> write(ServerHttpResponse response, int status, String code, String message, Duration retryAfter) {
        response.setStatusCode(HttpStatusCode.valueOf(status));
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        if (retryAfter != null) {
            response.getHeaders().set(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfter.toSeconds()));
        }

        var span = tracer.currentSpan();
        var traceId = span == null ? null : span.context().traceId();
        var body = FailureResponse.of(status, code, message, traceId);
        var buffer = response.bufferFactory().wrap(mapper.writeValueAsBytes(body));
        return response.writeWith(Mono.just(buffer));
    }
}
