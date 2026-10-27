package iseonjwa.gateway.security;

import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.cloud.client.circuitbreaker.ReactiveCircuitBreaker;
import org.springframework.cloud.client.circuitbreaker.ReactiveCircuitBreakerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GatewayAuthenticationFilterTest {
    private final AtomicReference<ServerWebExchange> forwarded = new AtomicReference<>();
    private final GatewayFilterChain chain = exchange -> {
        forwarded.set(exchange);
        return Mono.empty();
    };

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/admin/check", "/api/v1/admin", "/api/v1/admin;x=1/check",
            "/api/v1/%61dmin/check", "/api/%76%31/admin/check"})
    void USER는_라우터가_인식하는_관리자_경로를_통과하지_못한다(String path) {
        var exchange = exchange(HttpMethod.GET, path, true);

        filter("USER").filter(exchange, chain).block();

        assertEquals(HttpStatus.FORBIDDEN, exchange.getResponse().getStatusCode());
        assertNull(forwarded.get());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/admin/check", "/api/v1/admin", "/api/v1/admin;x=1/check"})
    void ADMIN은_같은_경로에서_검증된_헤더로_전달된다(String path) {
        filter("ADMIN").filter(exchange(HttpMethod.GET, path, true), chain).block();

        assertEquals("1", forwardedHeader("X-User-Id"));
        assertEquals("ADMIN", forwardedHeader("X-User-Role"));
    }

    @Test
    void 공개_경로는_토큰_없이_통과하고_클라이언트가_보낸_사용자_헤더를_지운다() {
        filter("USER").filter(exchange(HttpMethod.POST, "/api/v1/auth/login", false), chain).block();

        assertNotNull(forwarded.get());
        assertNull(forwardedHeader("X-User-Id"));
        assertNull(forwardedHeader("X-User-Role"));
    }

    @Test
    void 내부_경로는_유효한_토큰이_있어도_404로_막는다() {
        var exchange = exchange(HttpMethod.GET, "/api/v1/internal/payments", true);

        filter("ADMIN").filter(exchange, chain).block();

        assertEquals(HttpStatus.NOT_FOUND, exchange.getResponse().getStatusCode());
        assertNull(forwarded.get());
    }

    @Test
    void 보호_경로는_토큰이_없으면_401이다() {
        var exchange = exchange(HttpMethod.GET, "/api/v1/users/me", false);

        filter("USER").filter(exchange, chain).block();

        assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
        assertNull(forwarded.get());
    }

    @Test
    void 검증한_토큰의_사용자_정보로_클라이언트_헤더를_대체한다() {
        filter("USER").filter(exchange(HttpMethod.GET, "/api/v1/users/me", true), chain).block();

        assertEquals("1", forwardedHeader("X-User-Id"));
        assertEquals("USER", forwardedHeader("X-User-Role"));
    }

    private String forwardedHeader(String name) {
        return forwarded.get().getRequest().getHeaders().getFirst(name);
    }

    private MockServerWebExchange exchange(HttpMethod method, String path, boolean withToken) {
        var request = MockServerHttpRequest.method(method, URI.create("http://localhost" + path))
                .header("X-User-Id", "999")
                .header("X-User-Role", "ADMIN");
        if (withToken) {
            request.header("Authorization", "Bearer access");
        }
        return MockServerWebExchange.from(request.build());
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private GatewayAuthenticationFilter filter(String role) {
        var decoder = mock(JwtDecoder.class);
        var jwt = Jwt.withTokenValue("access")
                .header("alg", "HS256")
                .subject("1")
                .claim("jti", "test-jti")
                .claim("role", role)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .build();
        when(decoder.decode("access")).thenReturn(jwt);

        var redis = mock(ReactiveStringRedisTemplate.class);
        when(redis.hasKey(anyString())).thenReturn(Mono.just(false));

        ReactiveCircuitBreakerFactory factory = mock(ReactiveCircuitBreakerFactory.class);
        var breaker = mock(ReactiveCircuitBreaker.class);
        when(factory.create(anyString())).thenReturn(breaker);
        when(breaker.run(any(Mono.class))).thenAnswer(call -> call.getArgument(0));

        var writer = new GatewayErrorWriter(JsonMapper.builder().build(), mock(Tracer.class));
        return new GatewayAuthenticationFilter(decoder, redis, writer, factory);
    }
}
