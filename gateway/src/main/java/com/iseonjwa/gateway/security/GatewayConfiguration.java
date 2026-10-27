package com.iseonjwa.gateway.security;

import com.iseonjwa.common.auth.TokenDecoders;
import com.iseonjwa.common.auth.TokenType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Duration;

@Configuration(proxyBeanMethods = false)
class GatewayConfiguration {
    private static final Duration REDIS_STARTUP_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration REDIS_STARTUP_RETRY_DELAY = Duration.ofMillis(200);
    private static final int REDIS_STARTUP_RETRIES = 2;

    @Bean
    JwtDecoder accessTokenDecoder(@Value("${auth.access-secret}") String secret,
            @Value("${auth.issuer}") String issuer) {
        return TokenDecoders.create(secret, issuer, TokenType.ACCESS);
    }

    @Bean
    ApplicationRunner redisConnectionWarmup(ReactiveStringRedisTemplate redis) {
        // Note: 준비 완료 전에 실제 연결을 열어 첫 보호 요청의 100ms를 연결 수립에 쓰지 않게 한다.
        return args -> redis.execute(connection -> connection.ping()).next()
                .filter("PONG"::equals)
                .switchIfEmpty(Mono.error(new IllegalStateException("Redis 연결 준비 응답이 없습니다")))
                .timeout(REDIS_STARTUP_TIMEOUT)
                .retryWhen(Retry.fixedDelay(REDIS_STARTUP_RETRIES, REDIS_STARTUP_RETRY_DELAY))
                .block();
    }
}
