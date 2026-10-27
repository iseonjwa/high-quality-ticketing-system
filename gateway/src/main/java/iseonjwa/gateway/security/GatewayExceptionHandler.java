package iseonjwa.gateway.security;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.ErrorResponse;
import org.springframework.web.server.*;
import reactor.core.publisher.Mono;

@Component
@Order(-2)
public class GatewayExceptionHandler implements WebExceptionHandler {
    private final GatewayErrorWriter writer;
    public GatewayExceptionHandler(GatewayErrorWriter writer) { this.writer = writer; }

    public Mono<Void> handle(ServerWebExchange exchange, Throwable exception) {
        if (exchange.getResponse().isCommitted()) return Mono.error(exception);
        int status = exception instanceof ErrorResponse error ? error.getStatusCode().value() : 500;
        String code = switch (status) {
            case 404 -> "NOT_FOUND";
            case 405 -> "METHOD_NOT_ALLOWED";
            case 503 -> "SERVICE_UNAVAILABLE";
            default -> "INTERNAL_SERVER_ERROR";
        };
        if (exception instanceof ErrorResponse error) exchange.getResponse().getHeaders().addAll(error.getHeaders());
        return writer.write(exchange, status, code, status == 404 ? "대상을 찾을 수 없습니다" : "요청을 처리할 수 없습니다");
    }
}
