package iseonjwa.gateway.security;

import io.micrometer.tracing.Tracer;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.cloud.client.circuitbreaker.ReactiveCircuitBreaker;
import org.springframework.cloud.client.circuitbreaker.ReactiveCircuitBreakerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.*;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GatewayAuthenticationFilterTest {
    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/admin/check", "/api/v1/admin", "/api/v1/admin;x=1/check",
            "/api/v1/%61dmin/check", "/api/%76%31/admin/check"})
    void USER는_라우터가_인식하는_관리자_경로를_통과하지_못한다(String path) {
        var forwarded = new AtomicReference<ServerWebExchange>();
        var exchange = exchange(path);
        filter("USER").filter(exchange, capture(forwarded)).block();

        assertEquals(HttpStatus.FORBIDDEN, exchange.getResponse().getStatusCode());
        assertNull(forwarded.get());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/admin/check", "/api/v1/admin", "/api/v1/admin;x=1/check"})
    void ADMIN은_같은_경로에서_검증된_헤더로_전달된다(String path) {
        var forwarded = new AtomicReference<ServerWebExchange>();
        filter("ADMIN").filter(exchange(path), capture(forwarded)).block();

        assertNotNull(forwarded.get());
        assertEquals("1", forwarded.get().getRequest().getHeaders().getFirst("X-User-Id"));
        assertEquals("ADMIN", forwarded.get().getRequest().getHeaders().getFirst("X-User-Role"));
    }

    private MockServerWebExchange exchange(String path) {
        return MockServerWebExchange.from(MockServerHttpRequest.method(HttpMethod.GET,
                URI.create("http://localhost" + path)).header("Authorization", "Bearer access")
                .header("X-User-Id", "999").header("X-User-Role", "ADMIN").build());
    }

    private GatewayFilterChain capture(AtomicReference<ServerWebExchange> forwarded) {
        return exchange -> { forwarded.set(exchange); return Mono.empty(); };
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private GatewayAuthenticationFilter filter(String role) {
        var verifier = mock(GatewayJwtVerifier.class);
        var jwt = Jwt.withTokenValue("access").header("alg", "HS256").subject("1")
                .claim("jti", "test-jti").claim("role", role)
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300)).build();
        when(verifier.decode("access")).thenReturn(jwt);
        var redis = mock(ReactiveStringRedisTemplate.class);
        when(redis.hasKey(anyString())).thenReturn(Mono.just(false));
        ReactiveCircuitBreakerFactory factory = mock(ReactiveCircuitBreakerFactory.class);
        var breaker = mock(ReactiveCircuitBreaker.class);
        when(factory.create(anyString())).thenReturn(breaker);
        when(breaker.run(any(Mono.class))).thenAnswer(call -> call.getArgument(0));
        var writer = new GatewayErrorWriter(JsonMapper.builder().build(), mock(Tracer.class));
        return new GatewayAuthenticationFilter(verifier, redis, writer, factory);
    }
}
