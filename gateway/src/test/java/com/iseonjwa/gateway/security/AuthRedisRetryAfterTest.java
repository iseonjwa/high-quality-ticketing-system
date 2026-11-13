package com.iseonjwa.gateway.security;

import com.iseonjwa.gateway.security.AuthRedisFailures.Cause;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthRedisRetryAfterTest {
    private static final Duration SINGLE_FAILURE = Duration.ofSeconds(3);

    @ParameterizedTest
    @EnumSource(value = Cause.class, names = "BREAKER_OPEN", mode = EnumSource.Mode.EXCLUDE)
    void 닫힌_상태의_한_번_실패는_설정한_간격이다(Cause cause) {
        var retryAfter = new AuthRedisRetryAfter(registryWithOpenWait(Duration.ofSeconds(5)), SINGLE_FAILURE);

        assertThat(retryAfter.of(cause)).isEqualTo(SINGLE_FAILURE);
    }

    @Test
    void 차단기_열림_거절은_auth_redis_열림_시간이다() {
        var retryAfter = new AuthRedisRetryAfter(registryWithOpenWait(Duration.ofSeconds(5)), SINGLE_FAILURE);

        assertThat(retryAfter.of(Cause.BREAKER_OPEN)).isEqualTo(Duration.ofSeconds(5));
    }

    @Test
    void 열림_시간이_정수_초가_아니면_올림한다() {
        var retryAfter = new AuthRedisRetryAfter(registryWithOpenWait(Duration.ofMillis(1_500)), SINGLE_FAILURE);

        assertThat(retryAfter.of(Cause.BREAKER_OPEN)).isEqualTo(Duration.ofSeconds(2));
    }

    @Test
    void 등록소에_auth_redis가_아직_없으면_한_번_실패_간격을_쓴다() {
        var retryAfter = new AuthRedisRetryAfter(CircuitBreakerRegistry.ofDefaults(), SINGLE_FAILURE);

        assertThat(retryAfter.of(Cause.BREAKER_OPEN)).isEqualTo(SINGLE_FAILURE);
    }

    @Test
    void 한_번_실패_간격이_1초보다_짧으면_기동에서_막는다() {
        assertThatThrownBy(() -> new AuthRedisRetryAfter(CircuitBreakerRegistry.ofDefaults(), Duration.ofMillis(500)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static CircuitBreakerRegistry registryWithOpenWait(Duration openWait) {
        var registry = CircuitBreakerRegistry.of(CircuitBreakerConfig.custom().waitDurationInOpenState(openWait).build());
        registry.circuitBreaker(GatewayAuthenticationFilter.AUTH_REDIS_BREAKER);
        return registry;
    }
}
