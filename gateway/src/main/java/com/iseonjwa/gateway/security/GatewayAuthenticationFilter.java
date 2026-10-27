package com.iseonjwa.gateway.security;

import com.iseonjwa.common.auth.TokenType;
import com.iseonjwa.common.identity.IdentityHeaders;
import com.iseonjwa.common.identity.Roles;
import com.iseonjwa.gateway.security.AccessPolicy.Access;
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
    private static final Duration REDIS_TIMEOUT = Duration.ofMillis(100);

    private final JwtDecoder decoder;
    private final ReactiveStringRedisTemplate redis;
    private final GatewayErrorWriter errors;
    private final ReactiveCircuitBreaker redisBreaker;

    public GatewayAuthenticationFilter(JwtDecoder decoder, ReactiveStringRedisTemplate redis,
            GatewayErrorWriter errors, ReactiveCircuitBreakerFactory<?, ?> breakers) {
        this.decoder = decoder;
        this.redis = redis;
        this.errors = errors;
        this.redisBreaker = breakers.create("auth-redis");
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
        } catch (JwtException | IllegalArgumentException exception) {
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
        var lookup = redis.hasKey(BLACKLIST_PREFIX + jwt.getId()).timeout(REDIS_TIMEOUT);
        return redisBreaker.run(lookup).onErrorMap(exception -> new AuthDependencyUnavailable());
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
