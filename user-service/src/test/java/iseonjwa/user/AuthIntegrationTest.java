package iseonjwa.user;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.config.import=", "spring.cloud.config.enabled=false", "eureka.client.enabled=false"})
@ActiveProfiles("test")
class AuthIntegrationTest {
    private static final String PASSWORD = "test-only-password";
    private static final String ADMIN_EMAIL = "admin@test.local";
    private static final String ADMIN_PASSWORD = "test-only-admin-password";

    @LocalServerPort
    int port;

    @Autowired
    StringRedisTemplate redis;

    @Autowired
    JdbcTemplate jdbc;

    @MockitoSpyBean
    PasswordEncoder passwords;

    private final HttpClient client = HttpClient.newHttpClient();
    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    void 가입_로그인_내정보를_실제_PostgreSQL과_Redis로_처리한다() throws Exception {
        var email = email();
        var signup = post("/api/v1/users", Map.of("email", email, "password", PASSWORD, "nickname", "테스트"));
        assertEquals(201, signup.statusCode());
        var userId = data(signup).get("userId").asLong();

        var login = login(email);
        assertEquals(200, login.statusCode());
        assertEquals(1800, data(login).get("accessTokenExpiresIn").asLong());

        var me = get("/api/v1/users/me", userId, "USER");
        assertEquals(200, me.statusCode());
        assertEquals("USER", data(me).get("role").asString());
        assertEquals(email, data(me).get("email").asString());
    }

    @Test
    void 중복_이메일은_409이고_잘못된_비밀번호는_401이다() throws Exception {
        var email = register();

        var duplicated = post("/api/v1/users", Map.of("email", email, "password", PASSWORD, "nickname", "중복"));
        assertEquals(409, duplicated.statusCode());
        assertEquals(401, login(email, "wrong").statusCode());
        assertEquals(401, login(email()).statusCode());
    }

    @Test
    void 잘못된_입력과_인증정보_누락을_거절한다() throws Exception {
        var invalid = post("/api/v1/users", Map.of("email", "invalid", "password", "short", "nickname", ""));
        assertEquals(400, invalid.statusCode());
        assertEquals(401, send("GET", "/api/v1/users/me", null, null, null, null).statusCode());
    }

    @Test
    void Refresh를_회전하고_이전_토큰의_재사용을_거절한다() throws Exception {
        var token = data(login(register())).get("refreshToken").asString();

        var refreshed = refresh(token);
        assertEquals(200, refreshed.statusCode());
        var rotated = data(refreshed).get("refreshToken").asString();
        assertNotEquals(token, rotated);
        assertEquals(401, refresh(token).statusCode());
        assertEquals(200, refresh(rotated).statusCode());
    }

    @Test
    void Refresh_동시_재사용은_하나만_성공한다() throws Exception {
        var token = data(login(register())).get("refreshToken").asString();

        try (var executor = Executors.newFixedThreadPool(2)) {
            var gate = new CountDownLatch(1);
            Callable<Integer> action = () -> {
                gate.await();
                return refresh(token).statusCode();
            };
            var first = executor.submit(action);
            var second = executor.submit(action);
            gate.countDown();

            var statuses = Set.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS));
            assertEquals(Set.of(200, 401), statuses);
        }
    }

    @Test
    void 로그아웃은_Refresh를_폐기하고_Access를_블랙리스트에_등록한다() throws Exception {
        var tokens = data(login(register()));
        var access = tokens.get("accessToken").asString();
        var claims = claims(access);

        var logout = send("POST", "/api/v1/auth/logout", null, access, userId(claims), "USER");

        assertEquals(200, logout.statusCode());
        assertTrue(data(logout).isNull());
        assertEquals("1", redis.opsForValue().get("auth:blacklist:" + claims.get("jti").asString()));
        assertEquals(401, refresh(tokens.get("refreshToken").asString()).statusCode());
    }

    @Test
    void 새_로그인은_이전_Refresh를_대체하고_토큰_원문은_저장하지_않는다() throws Exception {
        var email = register();
        var first = data(login(email));
        var second = data(login(email));
        var claims = claims(second.get("accessToken").asString());

        var stored = redis.opsForValue().get("auth:refresh:" + claims.get("sub").asString());
        assertNotNull(stored);
        assertEquals(64, stored.length());
        assertNotEquals(second.get("refreshToken").asString(), stored);
        assertEquals(401, refresh(first.get("refreshToken").asString()).statusCode());
        assertEquals(200, refresh(second.get("refreshToken").asString()).statusCode());
    }

    @Test
    void Access를_Refresh로_사용할_수_없다() throws Exception {
        var tokens = data(login(register()));

        assertEquals(401, refresh(tokens.get("accessToken").asString()).statusCode());
        assertEquals(401, refresh("malformed.jwt.value").statusCode());
    }

    @Test
    void ASCII_72바이트는_로그인하고_73바이트_접미사는_거절한다() throws Exception {
        passwordBoundary("a".repeat(72), "a".repeat(72) + "a");
    }

    @Test
    void 다중_바이트_비밀번호도_UTF8_72바이트_경계를_적용한다() throws Exception {
        passwordBoundary("가".repeat(24), "가".repeat(24) + "a");
    }

    @Test
    void 로그인에_5회_실패하면_계정이_잠기고_올바른_비밀번호도_거절한다() throws Exception {
        var email = register();

        for (int attempt = 1; attempt <= 4; attempt++) {
            assertEquals(401, login(email, "wrong").statusCode(), attempt + "번째 실패");
        }
        var fifth = login(email, "wrong");
        assertEquals(423, fifth.statusCode());
        assertEquals("ACCOUNT_LOCKED", code(fifth));

        assertEquals(423, login(email).statusCode());
    }

    @Test
    void 로그인에_성공하면_실패_횟수가_초기화된다() throws Exception {
        var email = register();

        for (int attempt = 0; attempt < 4; attempt++) {
            login(email, "wrong");
        }
        assertEquals(200, login(email).statusCode());
        for (int attempt = 0; attempt < 4; attempt++) {
            assertEquals(401, login(email, "wrong").statusCode());
        }
        assertEquals(200, login(email).statusCode());
    }

    @Test
    void 관리자가_비밀번호를_초기화하면_잠금이_풀리고_임시_비밀번호는_변경해야_쓸_수_있다() throws Exception {
        var email = register();
        var before = data(login(email));
        var userId = userId(claims(before.get("accessToken").asString()));
        lockOut(email);
        assertEquals(423, login(email).statusCode());

        var reset = resetPassword(userId);
        assertEquals(200, reset.statusCode());
        assertEquals("no-store", reset.headers().firstValue("Cache-Control").orElse(""));
        var temporary = data(reset).get("temporaryPassword").asString();
        assertEquals(16, temporary.length());

        assertEquals(401, refresh(before.get("refreshToken").asString()).statusCode());
        assertEquals(401, login(email).statusCode());
        var temporaryLogin = login(email, temporary);
        assertEquals(403, temporaryLogin.statusCode());
        assertEquals("PASSWORD_CHANGE_REQUIRED", code(temporaryLogin));

        var changed = post("/api/v1/auth/password-change",
                Map.of("email", email, "currentPassword", temporary, "newPassword", "brand-new-password"));
        assertEquals(200, changed.statusCode());
        assertEquals(401, login(email, temporary).statusCode());
        assertEquals(200, login(email, "brand-new-password").statusCode());
    }

    @Test
    void 비밀번호_변경은_현재_비밀번호가_틀리면_실패_횟수를_올린다() throws Exception {
        var email = register();

        for (int attempt = 0; attempt < 4; attempt++) {
            var rejected = post("/api/v1/auth/password-change",
                    Map.of("email", email, "currentPassword", "wrong", "newPassword", "brand-new-password"));
            assertEquals(401, rejected.statusCode());
        }
        assertEquals(423, post("/api/v1/auth/password-change",
                Map.of("email", email, "currentPassword", "wrong", "newPassword", "brand-new-password")).statusCode());
    }

    @Test
    void 새_비밀번호가_현재와_같거나_짧으면_거절한다() throws Exception {
        var email = register();

        var same = post("/api/v1/auth/password-change",
                Map.of("email", email, "currentPassword", PASSWORD, "newPassword", PASSWORD));
        var shortPassword = post("/api/v1/auth/password-change",
                Map.of("email", email, "currentPassword", PASSWORD, "newPassword", "short"));

        assertEquals(400, same.statusCode());
        assertEquals(400, shortPassword.statusCode());
        assertEquals(200, login(email).statusCode());
    }

    @Test
    void 일반_사용자는_비밀번호를_초기화할_수_없고_없는_사용자는_404이다() throws Exception {
        var userId = userId(claims(data(login(register())).get("accessToken").asString()));

        var forbidden = send("POST", "/api/v1/admin/users/" + userId + "/password-reset", null, null, userId, "USER");
        assertEquals(403, forbidden.statusCode());

        var missing = resetPassword(Long.MAX_VALUE);
        assertEquals(404, missing.statusCode());
    }

    @Test
    void 비밀번호_초기화_중_이전_비밀번호로_시작한_로그인은_세션을_만들지_못한다() throws Exception {
        var email = register();
        var before = data(login(email));
        var userId = userId(claims(before.get("accessToken").asString()));
        var checking = new CountDownLatch(1);
        var resume = new CountDownLatch(1);
        pauseFirstPasswordCheck(email, checking, resume);

        try (var executor = Executors.newSingleThreadExecutor()) {
            var pending = executor.submit(() -> login(email));
            try {
                assertTrue(checking.await(10, TimeUnit.SECONDS));
                assertEquals(200, resetPassword(userId).statusCode());
            } finally {
                resume.countDown();
            }
            assertEquals(401, pending.get(10, TimeUnit.SECONDS).statusCode());
        }
        assertFalse(Boolean.TRUE.equals(redis.hasKey("auth:refresh:" + userId)));
        assertEquals(401, refresh(before.get("refreshToken").asString()).statusCode());
    }

    @Test
    void 비밀번호_변경_중_이전_비밀번호로_시작한_로그인은_세션을_만들지_못한다() throws Exception {
        var email = register();
        var before = data(login(email));
        var userId = userId(claims(before.get("accessToken").asString()));
        var checking = new CountDownLatch(1);
        var resume = new CountDownLatch(1);
        pauseFirstPasswordCheck(email, checking, resume);

        try (var executor = Executors.newSingleThreadExecutor()) {
            var pending = executor.submit(() -> login(email));
            try {
                assertTrue(checking.await(10, TimeUnit.SECONDS));
                assertEquals(200, post("/api/v1/auth/password-change",
                        Map.of("email", email, "currentPassword", PASSWORD,
                                "newPassword", "changed-during-login")).statusCode());
            } finally {
                resume.countDown();
            }
            assertEquals(401, pending.get(10, TimeUnit.SECONDS).statusCode());
        }
        assertFalse(Boolean.TRUE.equals(redis.hasKey("auth:refresh:" + userId)));
        assertEquals(200, login(email, "changed-during-login").statusCode());
    }

    private void pauseFirstPasswordCheck(String email, CountDownLatch checking, CountDownLatch resume) {
        var hash = jdbc.queryForObject("SELECT password_hash FROM users WHERE email = ?", String.class, email);
        var paused = new AtomicBoolean();
        doAnswer(invocation -> {
            if (paused.compareAndSet(false, true)) {
                checking.countDown();
                if (!resume.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("비밀번호 비교 재개를 기다리지 못했습니다");
                }
            }
            return invocation.callRealMethod();
        }).when(passwords).matches(eq(PASSWORD), eq(hash));
    }

    private void passwordBoundary(String valid, String oversized) throws Exception {
        var email = email();
        var signup = post("/api/v1/users", Map.of("email", email, "password", valid, "nickname", "바이트검증"));
        assertEquals(201, signup.statusCode());
        assertEquals(200, login(email, valid).statusCode());

        var rejected = login(email, oversized);
        assertEquals(400, rejected.statusCode());
        assertEquals("VALIDATION_FAILED", code(rejected));
    }

    private void lockOut(String email) throws Exception {
        for (int attempt = 0; attempt < 5; attempt++) {
            login(email, "wrong");
        }
    }

    private HttpResponse<String> resetPassword(long targetUserId) throws Exception {
        var admin = data(login(ADMIN_EMAIL, ADMIN_PASSWORD));
        var adminId = userId(claims(admin.get("accessToken").asString()));
        return send("POST", "/api/v1/admin/users/" + targetUserId + "/password-reset", null, null, adminId, "ADMIN");
    }

    private String email() {
        return UUID.randomUUID() + "@test.local";
    }

    private String register() throws Exception {
        var email = email();
        var signup = post("/api/v1/users", Map.of("email", email, "password", PASSWORD, "nickname", "테스트"));
        assertEquals(201, signup.statusCode());
        return email;
    }

    private HttpResponse<String> login(String email) throws Exception {
        return login(email, PASSWORD);
    }

    private HttpResponse<String> login(String email, String password) throws Exception {
        return post("/api/v1/auth/login", Map.of("email", email, "password", password));
    }

    private HttpResponse<String> refresh(String token) throws Exception {
        return post("/api/v1/auth/refresh", Map.of("refreshToken", token));
    }

    private JsonNode claims(String jwt) {
        return mapper.readTree(Base64.getUrlDecoder().decode(jwt.split("\\.")[1]));
    }

    private long userId(JsonNode claims) {
        return Long.parseLong(claims.get("sub").asString());
    }

    private JsonNode data(HttpResponse<String> response) {
        return mapper.readTree(response.body()).get("data");
    }

    private String code(HttpResponse<String> response) {
        return mapper.readTree(response.body()).get("code").asString();
    }

    private HttpResponse<String> post(String path, Object body) throws Exception {
        return send("POST", path, body, null, null, null);
    }

    private HttpResponse<String> get(String path, Long userId, String role) throws Exception {
        return send("GET", path, null, null, userId, role);
    }

    // Gateway를 거치지 않으므로 사용자 헤더를 직접 넣어 Gateway가 전달한 요청을 흉내 낸다.
    private HttpResponse<String> send(String method, String path, Object body, String access,
            Long userId, String role) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json");
        if (userId != null) {
            builder.header("X-User-Id", userId.toString()).header("X-User-Role", role);
        }
        if (access != null) {
            builder.header("Authorization", "Bearer " + access);
        }
        var publisher = body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body));
        return client.send(builder.method(method, publisher).build(), HttpResponse.BodyHandlers.ofString());
    }
}
