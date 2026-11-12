package com.iseonjwa.gateway.security;

import com.iseonjwa.common.auth.EntryTokenClaims;
import com.iseonjwa.common.auth.EntryTokenDecoder;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;

// 입장 토큰 필터(ISE-58). 실제 검증기와 발급기와 같은 claim으로 서명한 토큰을 쓴다.
class EntryTokenFilterTest {
    private static final String SECRET = "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff";
    private static final String USER_ID = "42";
    private static final long SCHEDULE_ID = 31;
    private static final String JTI = "entry-jti";

    private final AtomicReference<ServerWebExchange> forwarded = new AtomicReference<>();
    private final GatewayFilterChain chain = exchange -> {
        forwarded.set(exchange);
        return Mono.empty();
    };
    private final EntryTokenFilter filter = new EntryTokenFilter(new EntryTokenDecoder(SECRET),
            new GatewayErrorWriter(JsonMapper.builder().build(), mock(Tracer.class)));

    @Test
    void 정상_토큰이면_좌석_조회와_선점_요청을_서비스로_전달한다() {
        var token = entryToken(SECRET, USER_ID, SCHEDULE_ID, 300, "entry", "gateway");

        filter.filter(exchange(HttpMethod.GET, "/api/v1/schedules/31/seats", token, USER_ID), chain).block();
        assertNotNull(forwarded.getAndSet(null));
        filter.filter(exchange(HttpMethod.POST, "/api/v1/schedules/31/reservations", token, USER_ID), chain).block();
        assertNotNull(forwarded.get());
    }

    // ISE-207: 선점 API가 소모 여부를 jti로 확인하도록 검증한 토큰의 jti를 넘긴다(ADR 0053)
    @Test
    void 검증을_통과하면_토큰의_jti를_X_Entry_Token_Id로_넘긴다() {
        var token = entryToken(SECRET, USER_ID, SCHEDULE_ID, 300, "entry", "gateway");

        filter.filter(exchange(HttpMethod.POST, "/api/v1/schedules/31/reservations", token, USER_ID), chain).block();

        assertEquals(List.of(JTI), forwardedHeader(EntryTokenFilter.ENTRY_TOKEN_ID_HEADER));
    }

    // 클라이언트가 다른 토큰의 jti를 미리 넣어도 검증한 토큰의 jti 하나만 남는다
    @Test
    void 입장_토큰_경로에서_클라이언트가_보낸_X_Entry_Token_Id는_검증한_jti로_바뀐다() {
        var token = entryToken(SECRET, USER_ID, SCHEDULE_ID, 300, "entry", "gateway");
        var request = exchange(HttpMethod.POST, "/api/v1/schedules/31/reservations", token, USER_ID,
                "x-entry-token-id", "someone-elses-jti");

        filter.filter(request, chain).block();

        assertEquals(List.of(JTI), forwardedHeader(EntryTokenFilter.ENTRY_TOKEN_ID_HEADER));
    }

    // 입장 토큰이 필요 없는 경로에서도 지운다. 대소문자만 바꾼 이름도 같은 헤더다
    @ParameterizedTest
    @ValueSource(strings = {"POST /api/v1/schedules/31/queue/token", "POST /api/v1/lotteries/5/entries",
            "GET /api/v1/concerts/1"})
    void 대상이_아닌_경로에서도_클라이언트가_보낸_X_Entry_Token_Id를_지운다(String request) {
        var parts = request.split(" ");

        filter.filter(exchange(HttpMethod.valueOf(parts[0]), parts[1], null, USER_ID,
                "X-ENTRY-TOKEN-ID", "forged-jti"), chain).block();

        assertNull(forwardedHeader(EntryTokenFilter.ENTRY_TOKEN_ID_HEADER));
    }

    // 검증에 실패하면 클라이언트가 jti 헤더를 보냈어도 서비스로 보내지 않는다
    @Test
    void 검증에_실패하면_jti_헤더를_보냈어도_서비스로_전달하지_않는다() {
        var expired = entryToken(SECRET, USER_ID, SCHEDULE_ID, -1, "entry", "gateway");

        assertRejected(exchange(HttpMethod.POST, "/api/v1/schedules/31/reservations", expired, USER_ID,
                EntryTokenFilter.ENTRY_TOKEN_ID_HEADER, JTI));
    }

    @Test
    void 토큰이_없으면_403_ENTRY_TOKEN_INVALID다() {
        assertRejected(exchange(HttpMethod.GET, "/api/v1/schedules/31/seats", null, USER_ID));
    }

    @Test
    void 다른_키로_서명한_위조_토큰은_403이다() {
        var forged = entryToken(SECRET.replace('0', 'f'), USER_ID, SCHEDULE_ID, 300, "entry", "gateway");

        assertRejected(exchange(HttpMethod.GET, "/api/v1/schedules/31/seats", forged, USER_ID));
    }

    @Test
    void 만료된_토큰은_403이다() {
        var expired = entryToken(SECRET, USER_ID, SCHEDULE_ID, -1, "entry", "gateway");

        assertRejected(exchange(HttpMethod.POST, "/api/v1/schedules/31/reservations", expired, USER_ID));
    }

    // 완료 기준: 다른 회차의 토큰으로 /schedules/{다른 id}/seats·reservations 요청하면 403
    @Test
    void 다른_회차의_토큰은_좌석_조회와_선점_모두_403이다() {
        var otherSchedule = entryToken(SECRET, USER_ID, 99, 300, "entry", "gateway");

        assertRejected(exchange(HttpMethod.GET, "/api/v1/schedules/31/seats", otherSchedule, USER_ID));
        assertRejected(exchange(HttpMethod.POST, "/api/v1/schedules/31/reservations", otherSchedule, USER_ID));
    }

    // 다른 사용자의 입장 토큰을 빌려 쓰지 못한다(ADR 0015)
    @Test
    void 토큰_사용자가_로그인_사용자와_다르면_403이다() {
        var someoneElse = entryToken(SECRET, "7", SCHEDULE_ID, 300, "entry", "gateway");

        assertRejected(exchange(HttpMethod.GET, "/api/v1/schedules/31/seats", someoneElse, USER_ID));
    }

    // 완료 기준: 결제 토큰을 입장 토큰 자리에 넣어도 403(E14). 같은 키로 서명했더라도 typ·aud가 다르면 거절한다
    @Test
    void 결제_토큰을_입장_토큰_자리에_넣으면_403이다() {
        var paymentLike = entryToken(SECRET, USER_ID, SCHEDULE_ID, 300, "payment", "payment-service");

        assertRejected(exchange(HttpMethod.POST, "/api/v1/schedules/31/reservations", paymentLike, USER_ID));
    }

    @Test
    void 형식이_깨진_토큰은_403이다() {
        assertRejected(exchange(HttpMethod.GET, "/api/v1/schedules/31/seats", "not-a-jwt", USER_ID));
    }

    // 완료 기준: 대기열 API는 입장 토큰 없이 통과한다. 추첨 응모도 대기열을 거치지 않는다(D3)
    @ParameterizedTest
    @ValueSource(strings = {"POST /api/v1/schedules/31/queue", "GET /api/v1/schedules/31/queue/me",
            "POST /api/v1/schedules/31/queue/token", "POST /api/v1/lotteries/5/entries",
            "GET /api/v1/schedules/31", "GET /api/v1/concerts/1"})
    void 대상이_아닌_경로는_입장_토큰_없이_통과한다(String request) {
        var parts = request.split(" ");

        filter.filter(exchange(HttpMethod.valueOf(parts[0]), parts[1], null, USER_ID), chain).block();

        assertNotNull(forwarded.get());
    }

    // 라우터가 같은 경로로 보는 변형(matrix parameter, 인코딩)도 대상으로 판정한다(AccessPolicy와 같은 PathPattern)
    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/schedules/31;x=1/seats", "/api/v1/schedules/31/%73eats"})
    void 경로_변형으로_필터를_우회하지_못한다(String path) {
        assertRejected(exchange(HttpMethod.GET, path, null, USER_ID));
    }

    private void assertRejected(MockServerWebExchange exchange) {
        filter.filter(exchange, chain).block();

        assertEquals(HttpStatus.FORBIDDEN, exchange.getResponse().getStatusCode());
        var body = exchange.getResponse().getBodyAsString().block();
        assertEquals("ENTRY_TOKEN_INVALID", JsonMapper.builder().build().readTree(body).get("code").asString());
        assertNull(forwarded.get());
    }

    private List<String> forwardedHeader(String name) {
        return forwarded.get().getRequest().getHeaders().get(name);
    }

    // 인증 필터가 검증 뒤 넣는 X-User-Id를 흉내 낸다. extraHeader는 이름·값 쌍으로 클라이언트가 보낸 헤더를 더한다
    private MockServerWebExchange exchange(HttpMethod method, String path, String entryToken, String userId,
                                           String... extraHeader) {
        var request = MockServerHttpRequest.method(method, URI.create("http://localhost" + path))
                .header("X-User-Id", userId);
        for (int i = 0; i < extraHeader.length; i += 2) {
            request.header(extraHeader[i], extraHeader[i + 1]);
        }
        if (entryToken != null) {
            request.header(EntryTokenFilter.ENTRY_TOKEN_HEADER, entryToken);
        }
        return MockServerWebExchange.from(request.build());
    }

    // 발급기(JwtEntryTokenIssuer)와 같은 claim. lifetimeSeconds가 음수면 이미 만료됐다
    private String entryToken(String key, String subject, long scheduleId, long lifetimeSeconds, String type,
                              String audience) {
        var encoder = new NimbusJwtEncoder(new ImmutableSecret<>(key.getBytes(StandardCharsets.UTF_8)));
        var now = Instant.now();
        var claims = JwtClaimsSet.builder()
                .subject(subject)
                .audience(List.of(audience))
                .issuedAt(now.minusSeconds(60))
                .expiresAt(now.plusSeconds(lifetimeSeconds))
                .id(JTI)
                .claim(EntryTokenClaims.TYPE, type)
                .claim(EntryTokenClaims.SCHEDULE_ID, scheduleId)
                .build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
    }
}
