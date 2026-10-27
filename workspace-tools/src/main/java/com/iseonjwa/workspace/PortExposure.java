package com.iseonjwa.workspace;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public final class PortExposure {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Set<String> PUBLISHABLE = Set.of("gateway", "grafana", "kafka-ui", "prometheus", "zipkin");

    private PortExposure() {}

    public static List<String> violations(JsonNode configuration, boolean baseOnly) {
        var found = new ArrayList<String>();
        for (var entry : configuration.get("services").properties()) {
            var ports = entry.getValue().get("ports");
            if (ports == null || ports.isNull()) continue;
            for (var port : ports) {
                var location = entry.getKey() + ":" + port.get("published").asString();
                var address = port.get("host_ip");
                if (address == null || !"127.0.0.1".equals(address.asString())) {
                    found.add(location + ": 호스트 주소는 127.0.0.1이어야 합니다");
                }
                if (baseOnly && !PUBLISHABLE.contains(entry.getKey())) {
                    found.add(location + ": 기본 Compose에서 호스트에 열 수 없는 서비스입니다");
                }
            }
        }
        return found;
    }

    public static JsonNode load(Path root, List<String> files) throws Exception {
        var emptyEnvironment = Files.createTempFile("iseonjwa-empty-env-", ".env");
        try {
            var command = new ArrayList<>(List.of("docker", "compose", "--env-file", emptyEnvironment.toString()));
            for (var file : files) command.addAll(List.of("-f", file));
            command.addAll(List.of("config", "--no-interpolate", "--format", "json"));
            return JSON.readTree(Commands.capture(root, command, "", System.getenv(), Duration.ofSeconds(15)));
        } finally {
            Files.deleteIfExists(emptyEnvironment);
        }
    }

    public static void check(Path root) throws Exception {
        var found = new ArrayList<>(violations(load(root, List.of("compose.yml")), true));
        found.addAll(violations(load(root, List.of("compose.yml", "compose.local.yml")), false));
        if (!found.isEmpty()) throw new IllegalStateException(String.join("\n", found));
    }
}
