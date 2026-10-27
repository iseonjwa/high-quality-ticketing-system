package com.iseonjwa.workspace;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalAppsTest {
    private Map<String, String> values() {
        var values = new HashMap<String, String>();
        LocalEnvironment.KEYS.forEach(key -> values.put(key, "test-only-value"));
        return values;
    }

    @Test
    void 모든_앱은_업무와_관리를_loopback으로_실행한다() {
        for (var app : LocalApps.ALL) {
            var environment = LocalApps.environment(WorkspacePaths.root(), app, values(), Map.of());
            assertEquals("127.0.0.1", environment.get("SERVER_ADDRESS"), app.name());
            assertEquals("127.0.0.1", environment.get("MANAGEMENT_SERVER_ADDRESS"), app.name());
            assertEquals("127.0.0.1", environment.get("EUREKA_INSTANCE_IPADDRESS"), app.name());
            assertEquals("true", environment.get("EUREKA_INSTANCE_PREFERIPADDRESS"), app.name());
        }
    }

    @Test
    void 앱에_필요하지_않은_비밀값을_상속하지_않는다() {
        for (var name : List.of("config-server", "eureka", "gateway")) {
            var environment = LocalApps.environment(WorkspacePaths.root(), LocalApps.named(name), values(), values());
            assertFalse(environment.containsKey("JWT_REFRESH_SECRET"));
            assertFalse(environment.containsKey("DB_PASSWORD"));
            assertFalse(environment.containsKey("ADMIN_PASSWORD"));
        }
        var gateway = LocalApps.environment(WorkspacePaths.root(), LocalApps.named("gateway"), values(), Map.of());
        assertTrue(gateway.containsKey("JWT_ACCESS_SECRET"));
    }

    @Test
    void 필수_값이_없으면_값을_노출하지_않고_거절한다() {
        var values = values();
        values.put("DB_PASSWORD", "");
        var exception = assertThrows(IllegalArgumentException.class,
                () -> LocalApps.environment(WorkspacePaths.root(), LocalApps.named("user-service"), values, Map.of()));
        assertTrue(exception.getMessage().contains("DB_PASSWORD"));
        assertFalse(exception.getMessage().contains("test-only-value"));
    }

    @Test
    void Compose는_컨테이너_내부_바인딩을_유지한다() throws Exception {
        var environment = new HashMap<>(System.getenv());
        environment.putAll(values());
        var output = Commands.capture(WorkspacePaths.root(), List.of("docker", "compose", "-f", "compose.yml",
                "config", "--format", "json"), "", environment, Duration.ofSeconds(15));
        var configuration = JsonMapper.builder().build().readTree(output);
        for (var app : LocalApps.ALL) {
            var settings = configuration.get("services").get(app.name()).get("environment");
            assertEquals("0.0.0.0", settings.get("SERVER_ADDRESS").asString(), app.name());
            assertEquals("0.0.0.0", settings.get("MANAGEMENT_SERVER_ADDRESS").asString(), app.name());
        }
    }
}
