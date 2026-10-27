package iseonjwa.user;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.*;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.config.import=", "spring.cloud.config.enabled=false", "eureka.client.enabled=false"})
@ActiveProfiles("test")
class AuthIntegrationTest {
    @LocalServerPort int port;
    @Autowired StringRedisTemplate redis;
    private final HttpClient client = HttpClient.newHttpClient();
    private final JsonMapper mapper = JsonMapper.builder().build();
    private static final String PASSWORD = "test-only-password";

    @Test
    void 가입_로그인_내정보를_실제_PostgreSQL과_Redis로_처리한다() throws Exception {
        var email = email();
        var signup = request("POST", "/api/v1/users", Map.of("email", email, "password", PASSWORD, "nickname", "테스트"), null, null);
        assertEquals(201, signup.statusCode());
        var userId = data(signup).get("userId").asLong();
        var login = login(email);
        assertEquals(200, login.statusCode());
        assertEquals(1800, data(login).get("accessTokenExpiresIn").asLong());
        var me = request("GET", "/api/v1/users/me", null, null, userId);
        assertEquals(200, me.statusCode());
        assertEquals("USER", data(me).get("role").asString());
        assertEquals(email, data(me).get("email").asString());
    }

    @Test
    void 중복_이메일은_409이고_잘못된_비밀번호는_401이다() throws Exception {
        var email = register();
        assertEquals(409, request("POST", "/api/v1/users", Map.of("email", email, "password", PASSWORD, "nickname", "중복"), null, null).statusCode());
        assertEquals(401, request("POST", "/api/v1/auth/login", Map.of("email", email, "password", "wrong"), null, null).statusCode());
        assertEquals(401, login(email()).statusCode());
    }

    @Test
    void 잘못된_입력과_인증정보_누락을_거절한다() throws Exception {
        assertEquals(400, request("POST", "/api/v1/users", Map.of("email", "invalid", "password", "short", "nickname", ""), null, null).statusCode());
        assertEquals(401, request("GET", "/api/v1/users/me", null, null, null).statusCode());
    }

    @Test
    void Refresh를_회전하고_이전_토큰의_재사용을_거절한다() throws Exception {
        var token = data(login(register())).get("refreshToken").asString();
        var refreshed = refresh(token);
        assertEquals(200, refreshed.statusCode());
        assertNotEquals(token, data(refreshed).get("refreshToken").asString());
        assertEquals(401, refresh(token).statusCode());
        assertEquals(200, refresh(data(refreshed).get("refreshToken").asString()).statusCode());
    }

    @Test
    void Refresh_동시_재사용은_하나만_성공한다() throws Exception {
        var token = data(login(register())).get("refreshToken").asString();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var gate = new CountDownLatch(1);
            Callable<Integer> action = () -> { gate.await(); return refresh(token).statusCode(); };
            var a = executor.submit(action);
            var b = executor.submit(action);
            gate.countDown();
            assertEquals(Set.of(200, 401), Set.of(a.get(15, TimeUnit.SECONDS), b.get(15, TimeUnit.SECONDS)));
        }
    }

    @Test
    void 로그아웃은_Refresh를_폐기하고_Access를_블랙리스트에_등록한다() throws Exception {
        var email = register();
        var user = data(login(email));
        var access = user.get("accessToken").asString();
        var claims = mapper.readTree(Base64.getUrlDecoder().decode(access.split("\\.")[1]));
        var logout = request("POST", "/api/v1/auth/logout", null, access, Long.parseLong(claims.get("sub").asString()));
        assertEquals(200, logout.statusCode());
        assertTrue(data(logout).isNull());
        assertEquals("1", redis.opsForValue().get("auth:blacklist:" + claims.get("jti").asString()));
        assertEquals(401, refresh(user.get("refreshToken").asString()).statusCode());
    }

    @Test
    void 새_로그인은_이전_Refresh를_대체하고_토큰_원문은_저장하지_않는다() throws Exception {
        var email = register();
        var first = data(login(email));
        var second = data(login(email));
        var access = second.get("accessToken").asString();
        var claims = mapper.readTree(Base64.getUrlDecoder().decode(access.split("\\.")[1]));
        var stored = redis.opsForValue().get("auth:refresh:" + claims.get("sub").asString());
        assertNotNull(stored);
        assertEquals(64, stored.length());
        assertNotEquals(second.get("refreshToken").asString(), stored);
        assertEquals(401, refresh(first.get("refreshToken").asString()).statusCode());
        assertEquals(200, refresh(second.get("refreshToken").asString()).statusCode());
    }

    @Test
    void Access를_Refresh로_사용할_수_없다() throws Exception {
        var user = data(login(register()));
        assertEquals(401, refresh(user.get("accessToken").asString()).statusCode());
        assertEquals(401, refresh("malformed.jwt.value").statusCode());
    }

    private String email() { return UUID.randomUUID() + "@test.local"; }

    @Test
    void ASCII_72바이트는_로그인하고_73바이트_접미사는_거절한다() throws Exception {
        passwordBoundary("a".repeat(72), "a".repeat(72) + "a");
    }

    @Test
    void 다중_바이트_비밀번호도_UTF8_72바이트_경계를_적용한다() throws Exception {
        passwordBoundary("가".repeat(24), "가".repeat(24) + "a");
    }

    private void passwordBoundary(String valid, String oversized) throws Exception {
        var email = email();
        assertEquals(201, request("POST", "/api/v1/users", Map.of("email", email,
                "password", valid, "nickname", "바이트검증"), null, null).statusCode());
        assertEquals(200, request("POST", "/api/v1/auth/login", Map.of("email", email,
                "password", valid), null, null).statusCode());
        var rejected = request("POST", "/api/v1/auth/login", Map.of("email", email,
                "password", oversized), null, null);
        assertEquals(400, rejected.statusCode());
        assertEquals("VALIDATION_FAILED", mapper.readTree(rejected.body()).get("code").asString());
    }
    private String register() throws Exception {
        var email = email();
        assertEquals(201, request("POST", "/api/v1/users", Map.of("email", email, "password", PASSWORD, "nickname", "테스트"), null, null).statusCode());
        return email;
    }
    private HttpResponse<String> login(String email) throws Exception {
        return request("POST", "/api/v1/auth/login", Map.of("email", email, "password", PASSWORD), null, null);
    }
    private HttpResponse<String> refresh(String token) throws Exception {
        return request("POST", "/api/v1/auth/refresh", Map.of("refreshToken", token), null, null);
    }
    private JsonNode data(HttpResponse<String> response) {
        return mapper.readTree(response.body()).get("data");
    }
    private HttpResponse<String> request(String method, String path, Object body, String access, Long userId) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json");
        if (userId != null) builder.header("X-User-Id", userId.toString()).header("X-User-Role", "USER");
        if (access != null) builder.header("Authorization", "Bearer " + access);
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
}
