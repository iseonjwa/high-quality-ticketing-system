package com.iseonjwa.gateway.security;

import com.iseonjwa.common.auth.TokenDecoders;
import com.iseonjwa.common.auth.TokenType;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.cloud.client.circuitbreaker.ReactiveCircuitBreaker;
import org.springframework.cloud.client.circuitbreaker.ReactiveCircuitBreakerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GatewayAuthenticationFilterTest {
    private static final String SECRET = "test-access-secret-that-is-at-least-32-bytes-long";
    private static final String ISSUER = "iseonjwa-auth";

    private final AtomicReference<ServerWebExchange> forwarded = new AtomicReference<>();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    // 운영(config-repo/gateway.yml)과 같은 열림 시간 5초
    private final CircuitBreakerRegistry breakers = CircuitBreakerRegistry.of(CircuitBreakerConfig.custom()
            .waitDurationInOpenState(Duration.ofSeconds(5)).build());
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

    @Test
    void 만료된_토큰은_401_TOKEN_EXPIRED다() {
        var exchange = exchangeWithBearer(signedAccessToken(SECRET, -120));

        filterWith(realDecoder()).filter(exchange, chain).block();

        assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
        assertEquals("TOKEN_EXPIRED", responseCode(exchange));
        assertNull(forwarded.get());
    }

    @Test
    void 서명이_틀린_만료_토큰은_만료를_알리지_않고_401_UNAUTHORIZED다() {
        var exchange = exchangeWithBearer(signedAccessToken(SECRET + "other", -120));

        filterWith(realDecoder()).filter(exchange, chain).block();

        assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
        assertEquals("UNAUTHORIZED", responseCode(exchange));
    }

    @Test
    void 형식이_깨진_토큰은_401_UNAUTHORIZED다() {
        var exchange = exchangeWithBearer("not-a-jwt");

        filterWith(realDecoder()).filter(exchange, chain).block();

        assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
        assertEquals("UNAUTHORIZED", responseCode(exchange));
    }

    @Test
    void Redis_오류는_503과_한_번_실패의_Retry_After_3을_주며_원인을_센다() {
        var exchange = exchange(HttpMethod.GET, "/api/v1/users/me", true);

        filterWith(mockDecoder("USER"), Mono.error(new RedisConnectionFailureException("연결 끊김")))
                .filter(exchange, chain).block();

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, exchange.getResponse().getStatusCode());
        assertEquals("3", exchange.getResponse().getHeaders().getFirst("Retry-After"));
        assertEquals("SERVICE_UNAVAILABLE", responseCode(exchange));
        assertEquals("일시적으로 인증을 확인할 수 없습니다", responseMessage(exchange));
        assertNull(forwarded.get());
        assertEquals(1.0, failureCount("redis_error"));
    }

    @Test
    void 안쪽_Redis_조회_시한_초과는_lookup_timeout으로_세고_503이다() {
        var exchange = exchange(HttpMethod.GET, "/api/v1/users/me", true);

        filterWith(mockDecoder("USER"), Mono.never()).filter(exchange, chain).block();

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, exchange.getResponse().getStatusCode());
        assertEquals("3", exchange.getResponse().getHeaders().getFirst("Retry-After"));
        assertEquals(1.0, failureCount("lookup_timeout"));
        assertEquals(0.0, failureCount("time_limiter"));
    }

    @Test
    void 차단기가_열려_거절하면_503에_열림_시간만큼_Retry_After를_준다() {
        var exchange = exchange(HttpMethod.GET, "/api/v1/users/me", true);
        var open = CallNotPermittedException.createCallNotPermittedException(
                breakers.circuitBreaker(GatewayAuthenticationFilter.AUTH_REDIS_BREAKER));

        filterWith(mockDecoder("USER"), Mono.error(open)).filter(exchange, chain).block();

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, exchange.getResponse().getStatusCode());
        assertEquals("5", exchange.getResponse().getHeaders().getFirst("Retry-After"));
        assertEquals("SERVICE_UNAVAILABLE", responseCode(exchange));
        assertEquals(1.0, failureCount("breaker_open"));
    }

    private double failureCount(String cause) {
        return meters.get(AuthRedisFailures.METER_NAME).tag("cause", cause).counter().count();
    }

    private String responseMessage(MockServerWebExchange exchange) {
        var body = exchange.getResponse().getBodyAsString().block();
        return JsonMapper.builder().build().readTree(body).get("message").asString();
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

    private MockServerWebExchange exchangeWithBearer(String token) {
        var request = MockServerHttpRequest.get("http://localhost/api/v1/users/me")
                .header("Authorization", "Bearer " + token);
        return MockServerWebExchange.from(request.build());
    }

    private String responseCode(MockServerWebExchange exchange) {
        var body = exchange.getResponse().getBodyAsString().block();
        return JsonMapper.builder().build().readTree(body).get("code").asString();
    }

    private JwtDecoder realDecoder() {
        return TokenDecoders.create(SECRET, ISSUER, TokenType.ACCESS);
    }

    // 실제 발급기와 같은 claim으로 서명한 Access 토큰. lifetimeSeconds가 음수면 이미 만료됐다.
    private String signedAccessToken(String key, long lifetimeSeconds) {
        var encoder = new NimbusJwtEncoder(new ImmutableSecret<>(key.getBytes(StandardCharsets.UTF_8)));
        var now = Instant.now();
        var claims = JwtClaimsSet.builder()
                .issuer(ISSUER)
                .subject("1")
                .audience(List.of(TokenType.ACCESS.audience()))
                .issuedAt(now.minusSeconds(180))
                .expiresAt(now.plusSeconds(lifetimeSeconds))
                .id("test-jti")
                .claim(TokenType.TYPE_CLAIM, TokenType.ACCESS.type())
                .claim(TokenType.ROLE_CLAIM, "USER")
                .build();
        var header = JwsHeader.with(MacAlgorithm.HS256).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    private GatewayAuthenticationFilter filter(String role) {
        return filterWith(mockDecoder(role));
    }

    private JwtDecoder mockDecoder(String role) {
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
        return decoder;
    }

    private GatewayAuthenticationFilter filterWith(JwtDecoder decoder) {
        return filterWith(decoder, Mono.just(false));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private GatewayAuthenticationFilter filterWith(JwtDecoder decoder, Mono<Boolean> revokedLookup) {
        var redis = mock(ReactiveStringRedisTemplate.class);
        when(redis.hasKey(anyString())).thenReturn(revokedLookup);

        ReactiveCircuitBreakerFactory factory = mock(ReactiveCircuitBreakerFactory.class);
        var breaker = mock(ReactiveCircuitBreaker.class);
        when(factory.create(anyString())).thenReturn(breaker);
        when(breaker.run(any(Mono.class))).thenAnswer(call -> call.getArgument(0));

        var writer = new GatewayErrorWriter(JsonMapper.builder().build(), mock(Tracer.class));
        var failures = new AuthRedisFailures(meters, breakers);
        var retryAfter = new AuthRedisRetryAfter(breakers, Duration.ofSeconds(3));
        return new GatewayAuthenticationFilter(decoder, redis, writer, factory, failures, retryAfter, Duration.ofMillis(100));
    }
}
