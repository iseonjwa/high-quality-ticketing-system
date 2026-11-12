package com.iseonjwa.gateway.security;

import com.iseonjwa.common.auth.TokenDecoders;
import com.iseonjwa.common.auth.TokenType;
import com.iseonjwa.common.identity.IdentityHeaders;
import com.iseonjwa.common.identity.Roles;
import com.iseonjwa.gateway.security.AccessPolicy.Access;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.circuitbreaker.ReactiveCircuitBreaker;
import org.springframework.cloud.client.circuitbreaker.ReactiveCircuitBreakerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.time.Duration;

@Component
public class GatewayAuthenticationFilter implements GlobalFilter, Ordered {
    private static final String BEARER_PREFIX = "Bearer ";
    private static final String BLACKLIST_PREFIX = "auth:blacklist:";
    static final String AUTH_REDIS_BREAKER = "auth-redis";

    private final JwtDecoder decoder;
    private final ReactiveStringRedisTemplate redis;
    private final GatewayErrorWriter errors;
    private final ReactiveCircuitBreaker redisBreaker;
    private final AuthRedisFailures redisFailures;
    private final Duration redisTimeout;

    // Note: 운영 값은 config-repo/gateway.yml의 200ms다(ADR 0050). 기본값도 같게 둬 설정이 빠져도 느린 호출 기준(100ms)과 겹치지 않게 한다.
    public GatewayAuthenticationFilter(JwtDecoder decoder, ReactiveStringRedisTemplate redis,
            GatewayErrorWriter errors, ReactiveCircuitBreakerFactory<?, ?> breakers,
            AuthRedisFailures redisFailures, @Value("${auth.redis-timeout:200ms}") Duration redisTimeout) {
        this.decoder = decoder;
        this.redis = redis;
        this.errors = errors;
        this.redisBreaker = breakers.create(AUTH_REDIS_BREAKER);
        this.redisFailures = redisFailures;
        this.redisTimeout = redisTimeout;
    }

    @Override
    public int getOrder() {
        return -100;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        var request = exchange.getRequest();
        var access = AccessPolicy.of(request.getMethod(), request.getPath().pathWithinApplication());
        if (access == Access.INTERNAL) {
            return errors.write(exchange, 404, "NOT_FOUND", "대상을 찾을 수 없습니다");
        }
        if (access == Access.PUBLIC) {
            return chain.filter(withIdentity(exchange, null));
        }

        var token = bearerToken(request);
        if (token == null) {
            return errors.write(exchange, 401, "UNAUTHORIZED", "인증이 필요합니다");
        }
        Jwt jwt;
        try {
            jwt = decoder.decode(token);
        } catch (JwtException exception) {
            // 만료와 그 밖의 실패(위조·형식 오류·다른 종류 토큰)를 SA 11-3대로 나눠 응답한다.
            // 클라이언트는 둘 다 401이라 코드와 상관없이 재발급을 시도한다.
            return TokenDecoders.isExpired(exception)
                    ? errors.write(exchange, 401, "TOKEN_EXPIRED", "토큰이 만료됐습니다")
                    : errors.write(exchange, 401, "UNAUTHORIZED", "유효한 인증 토큰이 필요합니다");
        } catch (IllegalArgumentException exception) {
            return errors.write(exchange, 401, "UNAUTHORIZED", "유효한 인증 토큰이 필요합니다");
        }
        if (access == Access.ADMIN && !Roles.ADMIN.equals(jwt.getClaimAsString(TokenType.ROLE_CLAIM))) {
            return errors.write(exchange, 403, "FORBIDDEN", "접근 권한이 없습니다");
        }

        return isRevoked(jwt)
                .flatMap(revoked -> revoked
                        ? errors.write(exchange, 401, "TOKEN_REVOKED", "로그아웃된 토큰입니다")
                        : chain.filter(withIdentity(exchange, jwt)))
                .onErrorResume(AuthDependencyUnavailable.class,
                        exception -> errors.write(exchange, 503, "SERVICE_UNAVAILABLE",
                                "일시적으로 인증을 확인할 수 없습니다"));
    }

    private Mono<Boolean> isRevoked(Jwt jwt) {
        // Note: 안쪽 시한은 전용 예외로 낸다. 그대로 TimeoutException을 내면 차단기 바깥 TimeLimiter의 시한 초과와 구별할 수 없다.
        var lookup = redis.hasKey(BLACKLIST_PREFIX + jwt.getId())
                .timeout(redisTimeout, Mono.error(AuthRedisFailures.RedisLookupTimeout::new));
        return Mono.defer(() -> {
            var startedAt = System.nanoTime();
            return redisBreaker.run(lookup).onErrorMap(exception -> {
                redisFailures.record(exception, Duration.ofNanos(System.nanoTime() - startedAt));
                return new AuthDependencyUnavailable();
            });
        });
    }

    private String bearerToken(ServerHttpRequest request) {
        var authorization = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (authorization == null || !authorization.startsWith(BEARER_PREFIX)) {
            return null;
        }
        return authorization.substring(BEARER_PREFIX.length());
    }

    // 클라이언트가 보낸 사용자 헤더는 항상 지우고, 검증한 토큰의 값만 넣는다.
    private ServerWebExchange withIdentity(ServerWebExchange exchange, Jwt jwt) {
        return exchange.mutate()
                .request(request -> request.headers(headers -> {
                    headers.remove(IdentityHeaders.USER_ID);
                    headers.remove(IdentityHeaders.USER_ROLE);
                    if (jwt != null) {
                        headers.set(IdentityHeaders.USER_ID, jwt.getSubject());
                        headers.set(IdentityHeaders.USER_ROLE, jwt.getClaimAsString(TokenType.ROLE_CLAIM));
                    }
                }))
                .build();
    }

    private static final class AuthDependencyUnavailable extends RuntimeException {}
}
