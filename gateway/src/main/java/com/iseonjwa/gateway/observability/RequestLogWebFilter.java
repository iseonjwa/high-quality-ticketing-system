package com.iseonjwa.gateway.observability;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.ErrorResponse;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;

import java.util.concurrent.TimeUnit;

// 요청마다 메서드·경로·상태·처리 시간을 한 줄 남긴다(ISE-182). 서블릿 서비스의 RequestLogFilter와 같은 형식이다.
// traceId는 로그 패턴이 MDC에서 붙인다. 본문·헤더·쿼리 문자열은 남기지 않고, Gateway는 경로 템플릿이 없어 요청 경로를 쓴다.
// iseonjwa.request-log.enabled=false면 등록하지 않는다(부하 측정 때 로그 양 조절).
@Component
@ConditionalOnProperty(name = "iseonjwa.request-log.enabled", havingValue = "true", matchIfMissing = true)
public class RequestLogWebFilter implements WebFilter, Ordered {
    // Note: WebFlux의 트레이싱 관측 필터는 @Order(0)이다. 그 안쪽(1)에서 돌아야 traceId가 있고,
    // 요청을 거절하는 Gateway 필터(GlobalFilter)는 이 뒤에서 돌므로 401·403도 한 줄이 남는다.
    static final int ORDER = 1;
    private static final Logger log = LoggerFactory.getLogger(RequestLogWebFilter.class);
    private static final String ACTUATOR_PREFIX = "/actuator";

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        var path = exchange.getRequest().getPath().value();
        if (path.startsWith(ACTUATOR_PREFIX)) {
            return chain.filter(exchange);
        }
        var start = System.nanoTime();
        var failure = new Throwable[1];
        return chain.filter(exchange)
                .doOnError(error -> failure[0] = error)
                .doFinally(signal -> {
                    var millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
                    log.info("요청 {} {} {} {}ms", exchange.getRequest().getMethod(), path,
                            status(exchange, signal, failure[0]), millis);
                });
    }

    private static String status(ServerWebExchange exchange, SignalType signal, Throwable failure) {
        if (signal == SignalType.CANCEL) {
            return "CANCELLED";
        }
        if (failure != null) {
            return String.valueOf(failure instanceof ErrorResponse error ? error.getStatusCode().value() : 500);
        }
        var status = exchange.getResponse().getStatusCode();
        return String.valueOf(status != null ? status.value() : 200);
    }
}
