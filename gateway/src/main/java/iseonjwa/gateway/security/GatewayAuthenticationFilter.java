package iseonjwa.gateway.security;

import org.springframework.cloud.client.circuitbreaker.ReactiveCircuitBreakerFactory;
import org.springframework.cloud.gateway.filter.*;
import org.springframework.core.Ordered;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import java.time.Duration;
import java.util.List;

@Component
public class GatewayAuthenticationFilter implements GlobalFilter, Ordered {
    private static final PathPattern ADMIN_PATH = PathPatternParser.defaultInstance.parse("/api/v1/admin/**");
    private final GatewayJwtVerifier jwt;
    private final ReactiveStringRedisTemplate redis;
    private final GatewayErrorWriter errors;
    private final ReactiveCircuitBreakerFactory<?, ?> breakers;

    public GatewayAuthenticationFilter(GatewayJwtVerifier jwt, ReactiveStringRedisTemplate redis,
            GatewayErrorWriter errors, ReactiveCircuitBreakerFactory<?, ?> breakers) {
        this.jwt = jwt; this.redis = redis; this.errors = errors; this.breakers = breakers;
    }

    public int getOrder() { return -100; }

    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        var cleanRequest = exchange.getRequest().mutate().headers(headers -> {
            headers.remove("X-User-Id"); headers.remove("X-User-Role");
        }).build();
        var clean = exchange.mutate().request(cleanRequest).build();
        var path = cleanRequest.getPath().value();
        var method = cleanRequest.getMethod().name();
        if (("POST".equals(method) && List.of("/api/v1/users", "/api/v1/auth/login", "/api/v1/auth/refresh").contains(path))
                || ("GET".equals(method) && path.matches("/api/v1/concerts(?:/[^/]+)?"))) {
            return chain.filter(clean);
        }
        var authorization = cleanRequest.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (authorization == null || !authorization.startsWith("Bearer "))
            return errors.write(clean, 401, "UNAUTHORIZED", "인증이 필요합니다");
        Jwt claims;
        try { claims = jwt.decode(authorization.substring(7)); }
        catch (JwtException | IllegalArgumentException exception) {
            return errors.write(clean, 401, "UNAUTHORIZED", "유효한 인증 토큰이 필요합니다");
        }
        var role = claims.getClaimAsString("role");
        if (ADMIN_PATH.matches(cleanRequest.getPath().pathWithinApplication()) && !"ADMIN".equals(role))
            return errors.write(clean, 403, "FORBIDDEN", "접근 권한이 없습니다");
        var check = redis.hasKey("auth:blacklist:" + claims.getId()).timeout(Duration.ofMillis(100));
        return breakers.create("auth-redis").run(check)
                .onErrorMap(exception -> new AuthDependencyUnavailable())
                .flatMap(revoked -> {
            if (revoked) return errors.write(clean, 401, "TOKEN_REVOKED", "로그아웃된 토큰입니다");
            var request = cleanRequest.mutate().headers(headers -> {
                headers.set("X-User-Id", claims.getSubject()); headers.set("X-User-Role", role);
            }).build();
            return chain.filter(clean.mutate().request(request).build());
        }).onErrorResume(AuthDependencyUnavailable.class,
                exception -> errors.write(clean, 503, "SERVICE_UNAVAILABLE", "일시적으로 인증을 확인할 수 없습니다"));
    }

    private static final class AuthDependencyUnavailable extends RuntimeException {}
}
