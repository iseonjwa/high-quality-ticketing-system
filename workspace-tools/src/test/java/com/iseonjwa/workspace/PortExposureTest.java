package com.iseonjwa.workspace;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PortExposureTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private Map<String, Object> port(int published, String address) {
        var result = new HashMap<String, Object>();
        result.put("published", Integer.toString(published));
        result.put("target", published);
        if (address != null) result.put("host_ip", address);
        return result;
    }

    private JsonNode config(Map<String, Object> services) {
        return JSON.valueToTree(Map.of("services", services));
    }

    @Test
    void Gateway와_관리자_UI의_loopback_포트는_허용한다() {
        var configuration = config(Map.of("gateway", Map.of("ports", List.of(port(8080, "127.0.0.1"))),
                "grafana", Map.of("ports", List.of(port(3000, "127.0.0.1")))));
        assertTrue(PortExposure.violations(configuration, true).isEmpty());
    }

    @Test
    void 기본_Compose의_서비스_포트_공개는_거절한다() {
        var configuration = config(Map.of("user-service", Map.of("ports", List.of(port(8081, "127.0.0.1")))));
        var violations = PortExposure.violations(configuration, true);
        assertEquals(1, violations.size());
        assertTrue(violations.getFirst().contains("user-service"));
    }

    @Test
    void local_override의_loopback_포트는_허용한다() {
        var configuration = config(Map.of("user-service", Map.of("ports", List.of(port(8081, "127.0.0.1"))),
                "redis", Map.of("ports", List.of(port(6379, "127.0.0.1")))));
        assertTrue(PortExposure.violations(configuration, false).isEmpty());
    }

    @Test
    void loopback이_아닌_주소와_주소_누락은_거절한다() {
        for (var address : new String[] {"0.0.0.0", null, "192.168.0.2"}) {
            var configuration = config(Map.of("gateway", Map.of("ports", List.of(port(8080, address)))));
            assertEquals(1, PortExposure.violations(configuration, false).size());
        }
    }

    @Test
    void 포트가_없는_서비스는_허용한다() {
        assertTrue(PortExposure.violations(config(Map.of("redis", Map.of())), true).isEmpty());
    }
}
