package com.iseonjwa.gateway.security;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.circuitbreaker.resilience4j.ReactiveResilience4JCircuitBreakerFactory;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JConfigurationProperties;
import reactor.core.publisher.Mono;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AuthRedisBreakerEventsTest {
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private final Logger logger = (Logger) LoggerFactory.getLogger(AuthRedisBreakerEvents.class);

    @BeforeEach
    void attachAppender() {
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void detachAppender() {
        logger.detachAppender(logs);
    }

    @Test
    void 호출이_여러_번이어도_전환과_기준_초과를_한_번씩만_남긴다() {
        var breaker = factory().create(GatewayAuthenticationFilter.AUTH_REDIS_BREAKER);

        for (int i = 0; i < 10; i++) {
            breaker.run(Mono.error(new IllegalStateException("실패"))).onErrorResume(e -> Mono.empty()).block();
        }

        var messages = logs.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
        assertThat(messages).filteredOn(message -> message.contains("상태 전환")).singleElement().asString()
                .contains("from=CLOSED to=OPEN", "failureRate=100.0", "bufferedCalls=4", "failedCalls=4", "slowCalls=0");
        assertThat(messages).filteredOn(message -> message.contains("실패율 기준 초과")).singleElement().asString()
                .contains("failureRate=100.0", "threshold=50.0");
    }

    @Test
    void 다른_차단기에는_붙이지_않는다() {
        var other = factory().create("other");

        for (int i = 0; i < 10; i++) {
            other.run(Mono.error(new IllegalStateException("실패"))).onErrorResume(e -> Mono.empty()).block();
        }

        assertThat(logs.list).isEmpty();
    }

    private ReactiveResilience4JCircuitBreakerFactory factory() {
        var config = CircuitBreakerConfig.custom()
                .slidingWindowSize(4)
                .minimumNumberOfCalls(4)
                .failureRateThreshold(50)
                .build();
        var registry = CircuitBreakerRegistry.of(Map.of(
                GatewayAuthenticationFilter.AUTH_REDIS_BREAKER, config, "other", config));
        var factory = new ReactiveResilience4JCircuitBreakerFactory(registry, TimeLimiterRegistry.ofDefaults(), null,
                new Resilience4JConfigurationProperties());
        new AuthRedisBreakerEvents().customize(factory);
        return factory;
    }
}
