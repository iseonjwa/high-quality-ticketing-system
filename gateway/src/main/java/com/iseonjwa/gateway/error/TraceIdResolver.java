package com.iseonjwa.gateway.error;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.handler.TracingObservationHandler.TracingContext;
import org.springframework.http.server.reactive.observation.ServerRequestObservationContext;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

/**
 * WebFlux 요청의 추적 ID를 꺼낸다. WebFlux는 스레드가 바뀌어 MDC를 믿을 수 없으므로
 * 요청에 붙은 관측(Observation) 컨텍스트에서 현재 span을 찾는다. 추적이 꺼져 있으면 null이다.
 */
@Component
public class TraceIdResolver {

    public String resolve(ServerWebExchange exchange) {
        return ServerRequestObservationContext.findCurrent(exchange.getAttributes())
                .map(context -> (TracingContext) context.get(TracingContext.class))
                .map(TracingContext::getSpan)
                .map(Span::context)
                .map(TraceContext::traceId)
                .orElse(null);
    }
}
