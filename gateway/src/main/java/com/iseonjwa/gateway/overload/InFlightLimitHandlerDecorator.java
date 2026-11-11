package com.iseonjwa.gateway.overload;

import com.iseonjwa.gateway.security.GatewayErrorWriter;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.webflux.autoconfigure.WebHttpHandlerBuilderCustomizer;
import org.springframework.http.server.reactive.HttpHandler;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.adapter.WebHttpHandlerBuilder;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Gateway가 동시에 처리하는 요청 수를 묶는다. 넘친 요청은 서버 관측·요청 로그·라우팅·인증 전에 503으로 바로 거절한다.
 * 동시 수가 힙을 키우고(배포 서버 5차 OOM), 리틀의 법칙(처리 중 수 = 처리율 × 체류 시간)으로 지연도 묶는다(설계 메모 A1).
 * 자리는 요청 시작부터 응답 완료까지 잡고, 성공·오류·취소 어느 쪽으로 끝나도 한 번 반납한다.
 */
@Component
@ConditionalOnProperty(name = "iseonjwa.gateway.in-flight.enabled", havingValue = "true", matchIfMissing = true)
public class InFlightLimitHandlerDecorator implements WebHttpHandlerBuilderCustomizer {
    // Note: Spring Boot 4(spring-web 7)는 서버 관측(추적 스팬·http_server_requests)을 WebFilter가 아니라 HttpWebHandlerAdapter가
    // WebFilter 체인 전체를 감싸 연다. 그 바깥인 HttpHandler 데코레이터에서 거절해야 거절 한 건이 관측·요청 로그·라우트 매칭 비용을
    // 내지 않는다(배포 서버 6차: 상한 거절도 한 건당 Gateway CPU 0.66~0.71ms). 대신 거절된 요청에는 스팬·traceId·요청 로그가 없고
    // 거절 카운터만 남는다. 받은 요청의 관측은 그대로다. 액추에이터는 관리 포트(9080)의 별도 HttpHandler라 이 데코레이터를 거치지 않는다.
    static final String IN_FLIGHT_METER = "gateway.in.flight.requests";
    static final String LIMIT_METER = "gateway.in.flight.limit";
    static final String REJECTED_METER = "gateway.in.flight.rejected";
    static final String OVERLOADED = "GATEWAY_OVERLOADED";

    // 장애 시연 패널(ISE-134)이 과부하 중에도 동작해야 한다. 요청이 적어 부하에 거의 영향이 없다
    private static final PathPattern EXEMPT = PathPatternParser.defaultInstance.parse("/api/v1/admin/**");

    private final GatewayErrorWriter errors;
    private final int maxRequests;
    private final Duration retryAfter;
    private final AtomicInteger inFlight = new AtomicInteger();
    private final Counter rejected;

    public InFlightLimitHandlerDecorator(GatewayErrorWriter errors, MeterRegistry meters,
            @Value("${iseonjwa.gateway.in-flight.max-requests:64}") int maxRequests,
            @Value("${iseonjwa.gateway.in-flight.retry-after:2s}") Duration retryAfter) {
        if (maxRequests < 1) {
            throw new IllegalArgumentException("iseonjwa.gateway.in-flight.max-requests는 1 이상이어야 한다: " + maxRequests);
        }
        if (retryAfter.toSeconds() < 1) {
            throw new IllegalArgumentException("iseonjwa.gateway.in-flight.retry-after는 1초 이상이어야 한다: " + retryAfter);
        }
        this.errors = errors;
        this.maxRequests = maxRequests;
        this.retryAfter = retryAfter;
        Gauge.builder(IN_FLIGHT_METER, inFlight, AtomicInteger::get)
                .description("Gateway가 지금 처리 중인 요청 수(운영자 경로 제외)")
                .register(meters);
        Gauge.builder(LIMIT_METER, () -> maxRequests)
                .description("Gateway 동시 처리 요청 수 상한")
                .register(meters);
        this.rejected = Counter.builder(REJECTED_METER)
                .description("동시 처리 수 상한을 넘어 503으로 거절한 요청 수")
                .register(meters);
    }

    @Override
    public void customize(WebHttpHandlerBuilder builder) {
        builder.httpHandlerDecorator(this::decorate);
    }

    HttpHandler decorate(HttpHandler delegate) {
        return (request, response) -> handle(request, response, delegate);
    }

    private Mono<Void> handle(ServerHttpRequest request, ServerHttpResponse response, HttpHandler delegate) {
        if (EXEMPT.matches(request.getPath().pathWithinApplication())) {
            return delegate.handle(request, response);
        }
        // Note: 구독될 때 자리를 잡는다. 조립만 되고 구독되지 않는 Mono가 자리를 잡은 채 남지 않게 하려는 것이다.
        return Mono.defer(() -> {
            if (inFlight.incrementAndGet() > maxRequests) {
                inFlight.decrementAndGet();
                rejected.increment();
                return errors.write(response, 503, OVERLOADED, "요청이 많아 잠시 후 다시 시도해 주세요", retryAfter);
            }
            // delegate.handle이 예외를 바로 던져도 defer가 오류 신호로 바꾸므로 doFinally가 반납한다
            return Mono.defer(() -> delegate.handle(request, response))
                    .doFinally(signal -> inFlight.decrementAndGet());
        });
    }

    int inFlight() {
        return inFlight.get();
    }
}
