package com.iseonjwa.workspace;

import java.nio.file.Path;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class LocalApps {
    public record App(String name, int port, int managementPort) {}

    public static final List<App> ALL = List.of(new App("config-server", 8888, 19888),
            new App("eureka", 8761, 18761), new App("gateway", 8080, 18080),
            new App("user-service", 8081, 18081), new App("reservation-service", 8082, 18082),
            new App("payment-service", 8083, 18083), new App("mock-pg", 8090, 18090));

    private LocalApps() {}

    public static App named(String name) {
        return ALL.stream().filter(app -> app.name().equals(name)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("지원하지 않는 앱입니다: " + name));
    }

    public static ProcessBuilder process(Path root, App app, Map<String, String> values) {
        var jar = root.resolve(app.name() + "/build/libs/app.jar");
        if (!Files.isRegularFile(jar)) throw new IllegalStateException("앱 JAR가 없습니다. bootJar를 먼저 실행하세요");
        var builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Xms32m", "-Xmx192m", "-jar", jar.toString()).directory(root.toFile());
        builder.environment().clear();
        builder.environment().putAll(environment(root, app, values, System.getenv()));
        return builder;
    }

    public static Map<String, String> environment(Path root, App app, Map<String, String> values,
            Map<String, String> inherited) {
        var result = new HashMap<>(inherited);
        LocalEnvironment.KEYS.forEach(result::remove);
        result.putAll(Map.of("SPRING_PROFILES_ACTIVE", app.name().equals("config-server") ? "native" : "local",
                "CONFIG_REPO_PATH", root.resolve("config-repo").toUri().toString(),
                "CONFIG_SERVER_URL", "http://127.0.0.1:8888", "EUREKA_URL", "http://127.0.0.1:8761/eureka/",
                "REDIS_HOST", "127.0.0.1", "REDIS_PORT", "6379",
                "KAFKA_BOOTSTRAP_SERVERS", "127.0.0.1:19092", "ZIPKIN_URL", "http://127.0.0.1:9411/api/v2/spans"));
        result.putAll(Map.of("SERVER_ADDRESS", "127.0.0.1", "SERVER_PORT", Integer.toString(app.port()),
                "MANAGEMENT_SERVER_ADDRESS", "127.0.0.1", "MANAGEMENT_PORT", Integer.toString(app.managementPort()),
                "MANAGEMENT_SERVER_PORT", Integer.toString(app.managementPort()), "EUREKA_INSTANCE_IPADDRESS", "127.0.0.1",
                "EUREKA_INSTANCE_HOSTNAME", "localhost", "EUREKA_INSTANCE_PREFERIPADDRESS", "true"));
        if (Set.of("user-service", "reservation-service", "payment-service").contains(app.name())) {
            var database = app.name().replace("-service", "");
            var databasePort = switch (app.name()) {
                case "user-service" -> 5433;
                case "reservation-service" -> 5434;
                case "payment-service" -> 5435;
                default -> throw new IllegalArgumentException("지원하지 않는 DB입니다");
            };
            result.put("DB_URL", "jdbc:postgresql://127.0.0.1:" + databasePort + "/" + database);
            supply(result, values, "DB_USER", "DB_PASSWORD");
        }
        if (Set.of("gateway", "user-service").contains(app.name())) supply(result, values, "JWT_ACCESS_SECRET");
        if (app.name().equals("payment-service")) supply(result, values, "PAYMENT_TOKEN_SECRET");
        if (app.name().equals("user-service")) supply(result, values, "JWT_REFRESH_SECRET", "ADMIN_EMAIL", "ADMIN_PASSWORD");
        return result;
    }

    private static void supply(Map<String, String> target, Map<String, String> values, String... keys) {
        for (var key : keys) {
            var value = values.get(key);
            if (value == null || value.isBlank()) throw new IllegalArgumentException("필수 환경변수가 비었습니다: " + key);
            target.put(key, value);
        }
    }
}
