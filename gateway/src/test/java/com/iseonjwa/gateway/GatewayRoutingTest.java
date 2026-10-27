package com.iseonjwa.gateway;

import com.iseonjwa.gateway.auth.TokenBlacklist;
import com.iseonjwa.gateway.support.TestTokens;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;
import reactor.netty.http.server.HttpServerRequest;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 실제 Gateway를 띄우고 가짜 서비스(받은 헤더를 그대로 돌려줌)로 라우팅해 본다.
 * 헤더 위조 제거, 인증 헤더 추가, 실패 응답 형식을 확인한다 (SA 7-2, 11-1, 11-2).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GatewayRoutingTest {

    private static final DisposableServer downstream = HttpServer.create()
            .port(0)
            .route(routes -> routes
                    .get("/api/v1/users/echo", (request, response) -> response.sendString(echoHeaders(request)))
                    .post("/api/v1/auth/login", (request, response) -> response.sendString(echoHeaders(request))))
            .bindNow();

    @DynamicPropertySource
    static void routes(DynamicPropertyRegistry registry) {
        String prefix = "spring.cloud.gateway.server.webflux.routes";
        registry.add(prefix + "[0].id", () -> "user-service");
        registry.add(prefix + "[0].uri", () -> "http://localhost:" + downstream.port());
        registry.add(prefix + "[0].predicates[0]", () -> "Path=/api/v1/users/**,/api/v1/auth/**");
        registry.add(prefix + "[1].id", () -> "payment-service");
        registry.add(prefix + "[1].uri", () -> "http://localhost:1");
        registry.add(prefix + "[1].predicates[0]", () -> "Path=/api/v1/payments/**");
    }

    @AfterAll
    static void stopDownstream() {
        downstream.disposeNow();
    }

    @LocalServerPort
    int port;

    @MockitoBean
    TokenBlacklist tokenBlacklist;

    WebTestClient client;

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
        when(tokenBlacklist.isRevoked(anyString())).thenReturn(Mono.just(false));
    }

    @Test
    void 인증되면_위조한_헤더를_지우고_토큰의_사용자로_바꿔_보낸다() {
        client.get().uri("/api/v1/users/echo")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + TestTokens.valid("42", "USER", "jti-1"))
                .header("X-User-Id", "999")
                .header("X-User-Role", "ADMIN")
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class).isEqualTo("42|USER");
    }

    @Test
    void 인증_없이_통과하는_경로도_위조한_헤더는_지운다() {
        client.post().uri("/api/v1/auth/login")
                .header("X-User-Id", "999")
                .header("X-User-Role", "ADMIN")
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class).isEqualTo("null|null");
    }

    @Test
    void 토큰이_없으면_401_UNAUTHORIZED_실패_형태다() {
        client.get().uri("/api/v1/users/echo")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.status").isEqualTo(401)
                .jsonPath("$.code").isEqualTo("UNAUTHORIZED")
                .jsonPath("$.message").exists()
                .jsonPath("$.errors").doesNotExist();
    }

    @Test
    void 만료된_토큰은_401_TOKEN_EXPIRED다() {
        client.get().uri("/api/v1/users/echo")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + TestTokens.expired("42"))
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody().jsonPath("$.code").isEqualTo("TOKEN_EXPIRED");
    }

    @Test
    void 로그아웃된_토큰은_401_TOKEN_REVOKED다() {
        when(tokenBlacklist.isRevoked("jti-revoked")).thenReturn(Mono.just(true));

        client.get().uri("/api/v1/users/echo")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + TestTokens.valid("42", "USER", "jti-revoked"))
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody().jsonPath("$.code").isEqualTo("TOKEN_REVOKED");
    }

    @Test
    void 라우트가_없는_경로는_404_NOT_FOUND_실패_형태다() {
        client.get().uri("/api/v1/unknown")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + TestTokens.valid("42", "USER", "jti-1"))
                .exchange()
                .expectStatus().isNotFound()
                .expectBody()
                .jsonPath("$.status").isEqualTo(404)
                .jsonPath("$.code").isEqualTo("NOT_FOUND");
    }

    @Test
    void 서비스에_연결하지_못하면_503_SERVICE_UNAVAILABLE이다() {
        client.get().uri("/api/v1/payments/1")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + TestTokens.valid("42", "USER", "jti-1"))
                .exchange()
                .expectStatus().isEqualTo(503)
                .expectBody().jsonPath("$.code").isEqualTo("SERVICE_UNAVAILABLE");
    }

    private static Mono<String> echoHeaders(HttpServerRequest request) {
        return Mono.just(request.requestHeaders().get("X-User-Id") + "|" + request.requestHeaders().get("X-User-Role"));
    }
}
