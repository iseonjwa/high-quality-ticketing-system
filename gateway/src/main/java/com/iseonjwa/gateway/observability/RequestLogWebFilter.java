package com.iseonjwa.gateway.observability;

import com.iseonjwa.common.web.RequestLogPolicy;
import com.iseonjwa.common.web.RequestLogProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.ErrorResponse;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;

import java.util.concurrent.TimeUnit;

// 요청의 메서드·경로·상태·처리 시간을 한 줄 남긴다(ISE-182). 서블릿 서비스의 RequestLogFilter와 같은 형식이다.
// traceId는 로그 패턴이 MDC에서 붙인다. 본문·헤더·쿼리 문자열은 남기지 않고, Gateway는 경로 템플릿이 없어 요청 경로를 쓴다.
// 어떤 요청을 남길지는 iseonjwa.request-log.mode(all·problems)가 정한다(RequestLogPolicy). 운영 기본값은 config-repo/gateway.yml이다.
// iseonjwa.request-log.enabled=false면 등록하지 않는다(부하 측정 때 로그 양 조절).
@Component
@ConditionalOnProperty(name = "iseonjwa.request-log.enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(RequestLogProperties.class)
public class RequestLogWebFilter implements WebFilter, Ordered {
    // Note: Spring Boot 4(spring-web 7)는 서버 관측을 필터가 아니라 HttpWebHandlerAdapter가 WebFilter 체인 전체를 감싸 연다.
    // 그래서 이 필터가 돌 때는 traceId가 이미 있다. 인증 필터(GlobalFilter)는 이 뒤에서 돌므로 401·403도 판정 대상이 된다.
    // 동시 처리 수 상한 거절(InFlightLimitHandlerDecorator)은 관측보다 앞이라 이 필터까지 오지 않아 한 줄이 남지 않는다.
    static final int ORDER = 1;
    private static final Logger log = LoggerFactory.getLogger(RequestLogWebFilter.class);
    private static final String ACTUATOR_PREFIX = "/actuator";

    private final RequestLogPolicy policy;

    public RequestLogWebFilter(RequestLogProperties properties) {
        this.policy = properties.policy();
    }

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
                    var cancelled = signal == SignalType.CANCEL;
                    var status = cancelled ? 0 : status(exchange, failure[0]);
                    if (policy.shouldLog(status, cancelled, millis)) {
                        log.info("요청 {} {} {} {}ms", exchange.getRequest().getMethod(), path,
                                cancelled ? "CANCELLED" : status, millis);
                    }
                });
    }

    private static int status(ServerWebExchange exchange, Throwable failure) {
        if (failure != null) {
            return failure instanceof ErrorResponse error ? error.getStatusCode().value() : 500;
        }
        var status = exchange.getResponse().getStatusCode();
        return status != null ? status.value() : 200;
    }
}
