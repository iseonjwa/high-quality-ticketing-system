package com.iseonjwa.gateway.security;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.circuitbreaker.resilience4j.ReactiveResilience4JCircuitBreakerFactory;
import org.springframework.cloud.client.circuitbreaker.Customizer;
import org.springframework.stereotype.Component;

import java.util.function.Function;

/**
 * auth-redis 차단기의 상태 전환과 기준 초과를 WARN 한 줄씩 남긴다. 전환은 드물어 횟수를 제한하지 않는다.
 */
@Component
public class AuthRedisBreakerEvents implements Customizer<ReactiveResilience4JCircuitBreakerFactory> {
    private static final Logger log = LoggerFactory.getLogger(AuthRedisBreakerEvents.class);

    @Override
    public void customize(ReactiveResilience4JCircuitBreakerFactory factory) {
        // Note: 라이브러리는 차단기 customizer를 run() 호출마다 부른다(ReactiveResilience4JCircuitBreaker).
        // once로 감싸지 않으면 요청마다 이벤트 소비자가 하나씩 늘어 로그가 중복되고 메모리가 샌다.
        factory.addCircuitBreakerCustomizer(Customizer.once(this::subscribe, Function.identity()),
                GatewayAuthenticationFilter.AUTH_REDIS_BREAKER);
    }

    void subscribe(CircuitBreaker breaker) {
        var config = breaker.getCircuitBreakerConfig();
        breaker.getEventPublisher()
                .onStateTransition(event -> {
                    var metrics = breaker.getMetrics();
                    var transition = event.getStateTransition();
                    log.warn("auth-redis 차단기 상태 전환 from={} to={} failureRate={} slowCallRate={} bufferedCalls={} "
                                    + "failedCalls={} slowCalls={}",
                            transition.getFromState(), transition.getToState(),
                            metrics.getFailureRate(), metrics.getSlowCallRate(),
                            metrics.getNumberOfBufferedCalls(), metrics.getNumberOfFailedCalls(),
                            metrics.getNumberOfSlowCalls());
                })
                .onFailureRateExceeded(event -> log.warn("auth-redis 차단기 실패율 기준 초과 failureRate={} threshold={}",
                        event.getFailureRate(), config.getFailureRateThreshold()))
                .onSlowCallRateExceeded(event -> log.warn("auth-redis 차단기 느린 호출 비율 기준 초과 slowCallRate={} "
                                + "threshold={} slowCallDurationMs={}",
                        event.getSlowCallRate(), config.getSlowCallRateThreshold(),
                        config.getSlowCallDurationThreshold().toMillis()));
    }
}
