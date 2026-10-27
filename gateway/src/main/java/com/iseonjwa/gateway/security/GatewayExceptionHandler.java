package com.iseonjwa.gateway.security;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.ErrorResponse;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebExceptionHandler;
import reactor.core.publisher.Mono;

@Component
@Order(-2)
public class GatewayExceptionHandler implements WebExceptionHandler {
    private final GatewayErrorWriter writer;

    public GatewayExceptionHandler(GatewayErrorWriter writer) {
        this.writer = writer;
    }

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable exception) {
        if (exchange.getResponse().isCommitted()) {
            return Mono.error(exception);
        }

        int status = 500;
        if (exception instanceof ErrorResponse error) {
            status = error.getStatusCode().value();
            exchange.getResponse().getHeaders().addAll(error.getHeaders());
        }
        var message = status == 404 ? "대상을 찾을 수 없습니다" : "요청을 처리할 수 없습니다";
        return writer.write(exchange, status, codeOf(status), message);
    }

    private String codeOf(int status) {
        return switch (status) {
            case 404 -> "NOT_FOUND";
            case 405 -> "METHOD_NOT_ALLOWED";
            case 503 -> "SERVICE_UNAVAILABLE";
            default -> "INTERNAL_SERVER_ERROR";
        };
    }
}
