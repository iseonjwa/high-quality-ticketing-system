package iseonjwa.gateway.security;

import io.micrometer.tracing.Tracer;
import iseonjwa.common.response.ApiResponse;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

@Component
public class GatewayErrorWriter {
    private final ObjectMapper mapper;
    private final Tracer tracer;
    public GatewayErrorWriter(ObjectMapper mapper, Tracer tracer) { this.mapper = mapper; this.tracer = tracer; }

    public Mono<Void> write(ServerWebExchange exchange, int status, String code, String message) {
        var response = exchange.getResponse();
        response.setStatusCode(HttpStatusCode.valueOf(status));
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        if (status == 503) response.getHeaders().set(HttpHeaders.RETRY_AFTER, "1");
        var span = tracer.currentSpan();
        var body = new ApiResponse<>(status, code, message, null, null, span == null ? null : span.context().traceId());
        var buffer = response.bufferFactory().wrap(mapper.writeValueAsBytes(body));
        return response.writeWith(Mono.just(buffer));
    }
}
