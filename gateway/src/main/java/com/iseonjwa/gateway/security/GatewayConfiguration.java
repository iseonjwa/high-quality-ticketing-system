package com.iseonjwa.gateway.security;

import com.iseonjwa.common.auth.EntryTokenDecoder;
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

    // EntryTokenDecoder는 common의 일반 클래스라 컴포넌트 스캔 대상이 아니다. 키를 받아 여기서 빈으로 만든다.
    // 키가 없거나 32바이트보다 짧으면 생성자가 예외를 던져 Gateway가 기동하지 않는다(계약 ① 서명 키, ISE-135)
    @Bean
    EntryTokenDecoder entryTokenDecoder(@Value("${entry-token.secret}") String secret) {
        return new EntryTokenDecoder(secret);
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
