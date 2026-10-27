package com.iseonjwa.gateway.auth;

import com.iseonjwa.gateway.config.RedisCircuitBreakerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.client.circuitbreaker.ReactiveCircuitBreaker;
import org.springframework.cloud.client.circuitbreaker.ReactiveCircuitBreakerFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * 로그아웃된 Access 토큰 확인 ({@code auth:blacklist:{jti}}, SA 12-8).
 * <p>
 * Redis가 느리거나 죽어도 Gateway 전체가 느려지지 않게 100ms 타임아웃과 서킷 브레이커로 감싼다.
 * 실패하면 통과시킨다(fail-open). 로그아웃된 토큰이 최대 Access 유효 시간(30분) 동안 통할 수 있다는
 * 위험을 감수한 정책이다 (SA 9-5, 결정 기록 #48 잠정).
 */
@Component
public class TokenBlacklist {

    static final String KEY_PREFIX = "auth:blacklist:";

    private static final Logger log = LoggerFactory.getLogger(TokenBlacklist.class);

    private final ReactiveStringRedisTemplate redisTemplate;
    private final ReactiveCircuitBreaker circuitBreaker;

    public TokenBlacklist(ReactiveStringRedisTemplate redisTemplate,
                          ReactiveCircuitBreakerFactory<?, ?> circuitBreakerFactory) {
        this.redisTemplate = redisTemplate;
        this.circuitBreaker = circuitBreakerFactory.create(RedisCircuitBreakerConfig.REDIS);
    }

    public Mono<Boolean> isRevoked(String tokenId) {
        return circuitBreaker.run(
                redisTemplate.hasKey(KEY_PREFIX + tokenId),
                error -> {
                    log.warn("블랙리스트 확인 실패, fail-open으로 통과시킨다: {}", error.toString());
                    return Mono.just(false);
                });
    }
}
