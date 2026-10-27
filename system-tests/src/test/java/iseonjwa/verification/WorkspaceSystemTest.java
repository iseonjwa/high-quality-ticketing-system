package iseonjwa.verification;

import iseonjwa.workspace.JvmWorkspace;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static iseonjwa.verification.WorkspaceClient.BASE;
import static iseonjwa.verification.WorkspaceClient.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@Tag("system")
@TestMethodOrder(OrderAnnotation.class)
class WorkspaceSystemTest {
    private static WorkspaceClient client;
    private static JvmWorkspace jvm;

    @BeforeAll
    static void connect() throws Exception {
        if (Boolean.getBoolean("verification.manageJvm")) {
            jvm = new JvmWorkspace(WorkspaceClient.ROOT);
            jvm.start();
        }
        client = new WorkspaceClient();
        await("Gateway → User 준비", () -> client.request("POST", "/api/v1/users", Map.of(), null).status() == 400,
                Duration.ofSeconds(60));
    }

    @AfterAll
    static void close() {
        try {
            if (client != null) client.close();
        } finally {
            if (jvm != null) jvm.close();
        }
    }

    @Test
    @Order(1)
    void 재기동_뒤_첫_인증_요청이_User까지_도달한다() throws Exception {
        assumeTrue(Boolean.getBoolean("verification.coldStart"));
        client.docker("restart", "gateway");
        await("Gateway 재기동 준비", () -> client.request("GET",
                "http://127.0.0.1:18080/actuator/health/readiness", null, null, Map.of()).status() == 200,
                Duration.ofSeconds(60));
        await("재기동 후 User 탐색", () -> client.request("POST", "/api/v1/users", Map.of(), null).status() == 400,
                Duration.ofSeconds(60));
        var token = client.forge(Map.of("sub", Long.toString(Long.MAX_VALUE)), client.env("JWT_ACCESS_SECRET"));
        client.check("첫 인증 요청의 User 도달", "GET", "/api/v1/users/me", 404, null, token);
    }

    @Test
    @Order(10)
    void 가입_로그인_중복과_잘못된_자격_정보의_응답_계약을_지킨다() throws Exception {
        var user = client.account();
        client.check("중복 이메일", "POST", "/api/v1/users", 409,
                Map.of("email", user.email(), "password", user.password(), "nickname", "중복"), null);
        client.check("잘못된 비밀번호", "POST", "/api/v1/auth/login", 401,
                Map.of("email", user.email(), "password", "wrong"), null);
        client.check("없는 이메일", "POST", "/api/v1/auth/login", 401,
                Map.of("email", UUID.randomUUID() + "@test.local", "password", "wrong"), null);
    }

    @Test
    @Order(20)
    void 위조_사용자_헤더를_제거하고_서비스에_검증한_신원을_전달한다() throws Exception {
        var user = client.account();
        var spoofed = Map.of("X-User-Id", "99999999", "X-User-Role", "ADMIN");
        var me = client.check("내 정보·위조 헤더 제거", "GET", "/api/v1/users/me", 200,
                null, user.access(), spoofed).get("data");
        assertEquals(user.id(), me.get("userId").asLong());
        assertEquals("USER", me.get("role").asString());
        for (var service : List.of("reservations", "payments")) {
            var path = "/api/v1/" + service + "/_system/identity";
            await(service + " 탐색", () -> client.request("GET", path, null, user.access()).status() == 200,
                    Duration.ofSeconds(60));
            var identity = client.check(service + " 사용자 전달", "GET", path, 200,
                    null, user.access(), spoofed).get("data");
            assertEquals(user.id(), identity.get("userId").asLong());
            assertEquals("USER", identity.get("role").asString());
        }
    }

    @Test
    @Order(30)
    void 인증_누락과_관리자_경로_변형을_거절한다() throws Exception {
        var user = client.account();
        client.check("인증 누락", "GET", "/api/v1/users/me", 401, null, null);
        client.check("USER 관리자 접근", "POST", "/api/v1/admin/concerts", 403, Map.of(), user.access());
        for (var path : List.of("/api/v1/admin", "/api/v1/admin;x=1/review-probe",
                "/api/v1/%61dmin/review-probe", "/api/%76%31/admin/review-probe")) {
            client.check("관리자 경로 변형", "GET", path, 403, null, user.access());
        }
    }

    @Test
    @Order(40)
    void ASCII와_UTF8_비밀번호의_72바이트_경계를_지킨다() throws Exception {
        for (var valid : List.of("a".repeat(72), "가".repeat(24))) {
            var email = UUID.randomUUID() + "@test.local";
            client.check("72바이트 가입", "POST", "/api/v1/users", 201,
                    Map.of("email", email, "password", valid, "nickname", "바이트검증"), null);
            client.login(email, valid);
            client.check("73바이트 거절", "POST", "/api/v1/auth/login", 400,
                    Map.of("email", email, "password", valid + "a"), null);
        }
    }

    @Test
    @Order(50)
    void JWT_종류를_구분하고_Refresh_회전과_로그아웃을_적용한다() throws Exception {
        var user = client.account();
        var secret = client.env("JWT_ACCESS_SECRET");
        var invalid = List.of(
                client.forge(Map.of("exp", Instant.now().getEpochSecond() - 120), secret),
                client.forge(Map.of(), "invalid-signing-key-that-is-over-32-bytes"),
                client.forge(Map.of("iss", "other"), secret),
                client.forge(Map.of("aud", List.of("other")), secret),
                client.forge(Map.of("token_type", "refresh"), secret), user.refresh());
        for (var token : invalid) {
            client.check("잘못된 Access 거절", "GET", "/api/v1/users/me", 401, null, token);
        }
        client.check("Access의 Refresh 혼용", "POST", "/api/v1/auth/refresh", 401,
                Map.of("refreshToken", user.access()), null);
        var rotated = client.check("Refresh 회전", "POST", "/api/v1/auth/refresh", 200,
                Map.of("refreshToken", user.refresh()), null).get("data");
        client.check("이전 Refresh 재사용", "POST", "/api/v1/auth/refresh", 401,
                Map.of("refreshToken", user.refresh()), null);
        var access = rotated.get("accessToken").asString();
        assertTrue(client.check("로그아웃", "POST", "/api/v1/auth/logout", 200,
                Map.of(), access).get("data").isNull());
        client.check("로그아웃 Access", "GET", "/api/v1/users/me", 401, null, access);
        client.check("로그아웃 Refresh", "POST", "/api/v1/auth/refresh", 401,
                Map.of("refreshToken", rotated.get("refreshToken").asString()), null);
    }

    @Test
    @Order(60)
    void 잠금과_관리자_초기화_임시_비밀번호_변경을_적용한다() throws Exception {
        var user = client.account();
        var admin = client.login(client.env("ADMIN_EMAIL"), client.env("ADMIN_PASSWORD"));
        var adminAccess = admin.get("accessToken").asString();
        await("관리자 역할 전달 대상 준비", () -> client.request("GET",
                "/api/v1/reservations/_system/identity", null, adminAccess).status() == 200,
                Duration.ofSeconds(60));
        var identity = client.check("ADMIN 역할 전달", "GET", "/api/v1/reservations/_system/identity", 200,
                null, adminAccess).get("data");
        assertEquals("ADMIN", identity.get("role").asString());
        for (int attempt = 1; attempt <= 5; attempt++) {
            client.check("로그인 실패 " + attempt, "POST", "/api/v1/auth/login", attempt < 5 ? 401 : 423,
                    Map.of("email", user.email(), "password", "wrong"), null);
        }
        client.check("잠긴 계정 로그인", "POST", "/api/v1/auth/login", 423,
                Map.of("email", user.email(), "password", user.password()), null);
        client.check("잠긴 계정 재발급", "POST", "/api/v1/auth/refresh", 423,
                Map.of("refreshToken", user.refresh()), null);
        var path = "/api/v1/admin/users/" + user.id() + "/password-reset";
        client.check("USER 초기화 차단", "POST", path, 403, Map.of(), user.access());
        var reset = client.check("관리자 초기화", "POST", path, 200, Map.of(), adminAccess).get("data");
        client.check("초기화 뒤 이전 Refresh", "POST", "/api/v1/auth/refresh", 401,
                Map.of("refreshToken", user.refresh()), null);
        var temporary = reset.get("temporaryPassword").asString();
        client.check("임시 비밀번호 로그인 제한", "POST", "/api/v1/auth/login", 403,
                Map.of("email", user.email(), "password", temporary), null);
        var changed = WorkspaceClient.randomHex();
        client.check("비밀번호 변경", "POST", "/api/v1/auth/password-change", 200,
                Map.of("email", user.email(), "currentPassword", temporary, "newPassword", changed), null);
        client.login(user.email(), changed);
    }

    @Test
    @Order(70)
    void Reservation의_진단_이벤트를_Kafka를_거쳐_Payment가_소비한다() throws Exception {
        var user = client.account();
        await("진단 이벤트 발행 대상 준비", () -> client.request("GET",
                "/api/v1/reservations/_system/identity", null, user.access()).status() == 200,
                Duration.ofSeconds(60));
        var event = client.check("진단 이벤트 발행", "POST", "/api/v1/reservations/_system/events", 202,
                Map.of(), user.access()).get("data").get("eventId").asString();
        var path = "/api/v1/payments/_system/events/" + event;
        await("Kafka 소비", () -> client.request("GET", path, null, user.access()).status() == 200,
                Duration.ofSeconds(60));
        client.check("진단 이벤트 결과 조회", "GET", path, 200, null, user.access());
    }

    @Test
    @Order(80)
    void 관리_엔드포인트를_Gateway에_공개하지_않는다() throws Exception {
        for (var path : List.of("/actuator/prometheus", "/eureka/apps", "/config-server/default", "/mock-pg/payments")) {
            client.check("관리 경로 비공개", "GET", path, 404, null, null);
        }
    }

    @Test
    @Order(81)
    void Config_응답에_실제_비밀값이_노출되지_않는다() throws Exception {
        for (var app : List.of("user-service", "reservation-service", "payment-service", "gateway", "mock-pg", "eureka")) {
            var response = client.request("GET", "http://127.0.0.1:8888/" + app + "/local", null, null, Map.of());
            assertEquals(200, response.status(), app + " Config 조회");
            assertTrue(response.body().get("propertySources").size() > 0, app + " 설정 누락");
            var serialized = response.body().toString();
            for (var secret : client.secretValues()) {
                assertFalse(serialized.contains(secret), "Config 응답의 비밀값 노출");
            }
        }
    }

    @Test
    @Order(82)
    void Eureka에_업무_앱_5개가_등록된다() throws Exception {
        await("Eureka 앱 5개 등록", () -> {
            var response = client.request("GET", "http://127.0.0.1:8761/eureka/apps", null, null,
                    Map.of("Accept", "application/json"));
            if (response.status() != 200) return false;
            var names = new HashSet<String>();
            for (var app : response.body().get("applications").get("application")) {
                names.add(app.get("name").asString());
                if (Boolean.getBoolean("verification.jvmMode")) {
                    for (var instance : app.get("instance")) {
                        assertEquals("127.0.0.1", instance.get("ipAddr").asString(), "JVM 모드의 Eureka 등록 주소");
                    }
                }
            }
            return names.containsAll(Set.of("GATEWAY", "USER-SERVICE", "RESERVATION-SERVICE", "PAYMENT-SERVICE", "MOCK-PG"));
        }, Duration.ofSeconds(40));
    }

    @Test
    @Order(90)
    void 메트릭_대시보드와_Gateway_User_추적이_연결된다() throws Exception {
        var user = client.account();
        var traceId = WorkspaceClient.randomHex();
        client.check("추적 요청", "GET", "/api/v1/users/me", 200, null, user.access(),
                Map.of("traceparent", "00-" + traceId + "-" + WorkspaceClient.randomHex().substring(0, 16) + "-01"));
        await("Prometheus 앱 7개 수집", () -> {
            var response = client.request("GET", "http://127.0.0.1:9090/api/v1/targets", null, null, Map.of());
            int up = 0;
            for (var target : response.body().get("data").get("activeTargets")) {
                if ("up".equals(target.get("health").asString())) up++;
            }
            return up == 7;
        }, Duration.ofSeconds(60));
        var credentials = Base64.getEncoder().encodeToString(
                ("admin:" + client.env("GRAFANA_ADMIN_PASSWORD")).getBytes(StandardCharsets.UTF_8));
        assertEquals(200, client.request("GET", "http://127.0.0.1:3000/api/dashboards/uid/iseonjwa-bootstrap",
                null, null, Map.of("Authorization", "Basic " + credentials)).status());
        var clusters = client.request("GET", "http://127.0.0.1:8085/api/clusters", null, null, Map.of());
        assertEquals(200, clusters.status());
        boolean online = false;
        for (var cluster : clusters.body()) {
            online |= "iseonjwa".equals(cluster.get("name").asString())
                    && "ONLINE".equals(cluster.get("status").asString());
        }
        assertTrue(online, "Kafka-UI 클러스터 상태");
        await("Gateway와 User의 동일 trace", () -> {
            var response = client.request("GET", "http://127.0.0.1:9411/api/v2/trace/" + traceId, null, null, Map.of());
            var services = new HashSet<String>();
            if (response.status() != 200 || response.body() == null) return false;
            for (var span : response.body()) {
                var endpoint = span.get("localEndpoint");
                if (endpoint != null && endpoint.has("serviceName")) services.add(endpoint.get("serviceName").asString());
            }
            return services.containsAll(Set.of("gateway", "user-service"));
        }, Duration.ofSeconds(60));
    }

    @Test
    @Order(100)
    void Redis_장애는_503으로_구분하고_복구_뒤_새_인증이_성공한다() throws Exception {
        assumeTrue(Boolean.getBoolean("verification.redisFailure"));
        var user = client.account();
        var restore = new Thread(() -> {
            try {
                client.docker("unpause", "redis");
            } catch (Exception | AssertionError ignored) {
                // 종료 중 복구는 한 번 더 시도하되 비밀값이나 원본 출력을 남기지 않는다.
            }
        }, "system-test-redis-restore");
        Runtime.getRuntime().addShutdownHook(restore);
        boolean restored = false;
        try {
            client.docker("pause", "redis");
            client.check("Redis 지연 시 보호 API", "GET", "/api/v1/users/me", 503, null, user.access());
            client.check("Redis 장애 중 로그인", "POST", "/api/v1/auth/login", 503,
                    Map.of("email", user.email(), "password", user.password()), null);
            assertEquals(503, client.request("POST", "http://127.0.0.1:8081/api/v1/auth/logout", Map.of(),
                    user.access(), Map.of("X-User-Id", Long.toString(user.id()), "X-User-Role", "USER")).status());
        } finally {
            try {
                client.docker("unpause", "redis");
                restored = true;
            } finally {
                if (restored) Runtime.getRuntime().removeShutdownHook(restore);
            }
        }
        var access = new AtomicReference<String>();
        await("Redis 복구 후 로그인", () -> {
            var response = client.request("POST", "/api/v1/auth/login",
                    Map.of("email", user.email(), "password", user.password()), null);
            if (response.status() != 200) return false;
            access.set(response.body().get("data").get("accessToken").asString());
            return true;
        }, Duration.ofSeconds(35));
        await("Redis 복구 후 인증", () -> client.request("GET", "/api/v1/users/me", null, access.get()).status() == 200,
                Duration.ofSeconds(35));
    }
}
