package com.iseonjwa.verification;

import com.iseonjwa.workspace.Commands;
import com.iseonjwa.workspace.LocalEnvironment;
import com.iseonjwa.workspace.WorkspacePaths;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class WorkspaceClient implements AutoCloseable {
    static final String BASE = "http://127.0.0.1:8080";
    static final Path ROOT = WorkspacePaths.root();
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final Map<String, String> environment;

    record Response(int status, JsonNode body) {}
    record Account(long id, String email, String password, String access, String refresh) {}

    WorkspaceClient() throws Exception {
        environment = LocalEnvironment.load(ROOT.resolve(".env"));
    }

    String env(String key) {
        var value = environment.get(key);
        assertTrue(value != null && !value.isBlank(), "시스템 검사에 필요한 환경변수가 없습니다: " + key);
        return value;
    }

    List<String> secretValues() {
        return environment.entrySet().stream()
                .filter(entry -> entry.getKey().contains("SECRET") || entry.getKey().contains("PASSWORD"))
                .map(Map.Entry::getValue).filter(value -> !value.isBlank()).toList();
    }

    Response request(String method, String url, Object body, String token, Map<String, String> headers)
            throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(8))
                .header("Content-Type", "application/json");
        var supplied = new LinkedHashMap<>(headers);
        supplied.putIfAbsent("traceparent", "00-" + randomHex() + "-" + randomHex().substring(0, 16) + "-01");
        supplied.forEach(builder::header);
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
        var response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        JsonNode parsed = null;
        try {
            parsed = JSON.readTree(response.body());
        } catch (RuntimeException ignored) {
            // 관리 엔드포인트는 JSON이 아닐 수 있다. 계약 검사에서는 별도로 JSON 여부를 확인한다.
        }
        return new Response(response.statusCode(), parsed);
    }

    Response request(String method, String path, Object body, String token) throws Exception {
        return request(method, BASE + path, body, token, Map.of());
    }

    JsonNode check(String label, String method, String path, int expected, Object body, String token)
            throws Exception {
        return check(label, method, path, expected, body, token, Map.of());
    }

    JsonNode check(String label, String method, String path, int expected, Object body, String token,
            Map<String, String> headers) throws Exception {
        var response = request(method, BASE + path, body, token, headers);
        assertEquals(expected, response.status(), label);
        var json = response.body();
        assertNotNull(json, label + ": JSON 응답이 필요하다");
        Set<String> keys;
        if (expected >= 200 && expected < 300) {
            keys = Set.of("status", "message", "data", "traceId");
        } else if (expected == 400 && json.has("errors")) {
            keys = Set.of("status", "code", "message", "errors", "traceId");
        } else {
            keys = Set.of("status", "code", "message", "traceId");
        }
        assertEquals(keys.size(), json.size(), label + ": 응답 필드 수");
        for (var key : keys) {
            assertTrue(json.has(key), label + ": 필드 누락 " + key);
        }
        assertEquals(expected, json.get("status").asInt(), label + ": HTTP와 본문 상태");
        return json;
    }

    Account account() throws Exception {
        var email = "system-" + UUID.randomUUID() + "@test.local";
        var password = randomHex();
        var created = check("회원가입", "POST", "/api/v1/users", 201,
                Map.of("email", email, "password", password, "nickname", "시스템검증"), null).get("data");
        var pair = login(email, password);
        return new Account(created.get("userId").asLong(), email, password,
                pair.get("accessToken").asString(), pair.get("refreshToken").asString());
    }

    JsonNode login(String email, String password) throws Exception {
        return check("로그인", "POST", "/api/v1/auth/login", 200,
                Map.of("email", email, "password", password), null).get("data");
    }

    String forge(Map<String, Object> overrides, String secret) throws Exception {
        var claims = new LinkedHashMap<String, Object>(Map.of("sub", "99999999", "jti", UUID.randomUUID().toString(),
                "role", "USER", "iss", "iseonjwa-auth", "aud", List.of("iseonjwa-api"), "token_type", "access",
                "iat", Instant.now().getEpochSecond() - 5, "exp", Instant.now().getEpochSecond() + 1800));
        claims.putAll(overrides);
        var encoder = Base64.getUrlEncoder().withoutPadding();
        var input = encoder.encodeToString(JSON.writeValueAsBytes(Map.of("alg", "HS256", "typ", "JWT")))
                + "." + encoder.encodeToString(JSON.writeValueAsBytes(claims));
        var mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return input + "." + encoder.encodeToString(mac.doFinal(input.getBytes(StandardCharsets.UTF_8)));
    }

    void docker(String... arguments) throws Exception {
        var command = new ArrayList<>(List.of("docker", "compose", "-f", "compose.yml", "-f", "compose.local.yml"));
        command.addAll(List.of(arguments));
        Commands.capture(ROOT, command, "", System.getenv(), Duration.ofSeconds(30));
    }

    static void await(String label, Callable<Boolean> ready, Duration timeout) throws Exception {
        var deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            try {
                if (ready.call()) {
                    return;
                }
            } catch (Exception ignored) {
                // 준비 중 연결 실패는 제한 시간 안에서 다시 확인한다.
            }
            Thread.sleep(500);
        }
        throw new AssertionError(label + ": 제한 시간 초과");
    }

    static String randomHex() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    @Override
    public void close() {
        http.close();
    }
}
