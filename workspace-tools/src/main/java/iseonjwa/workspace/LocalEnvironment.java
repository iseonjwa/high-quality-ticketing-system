package iseonjwa.workspace;

import tools.jackson.databind.json.JsonMapper;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class LocalEnvironment {
    public static final List<String> KEYS = List.of("DB_USER", "DB_PASSWORD", "JWT_ACCESS_SECRET",
            "JWT_REFRESH_SECRET", "ADMIN_EMAIL", "ADMIN_PASSWORD", "GRAFANA_ADMIN_PASSWORD");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private LocalEnvironment() {}

    public static Map<String, String> load(Path path) throws Exception {
        return load(path, System.getenv());
    }

    public static Map<String, String> load(Path path, Map<String, String> environment) throws Exception {
        path = path.toAbsolutePath().normalize();
        if (!Files.isRegularFile(path)) {
            throw new IllegalArgumentException("개인 환경변수 파일이 없습니다. ./gradlew initLocalEnv를 실행하세요");
        }
        var expressions = new LinkedHashMap<String, String>();
        for (var key : KEYS) {
            expressions.put(key, "${" + key + (key.equals("DB_USER") ? ":-iseonjwa}" : "-}"));
        }
        var definition = Map.of("name", "iseonjwa-env-parser", "services",
                Map.of("values", Map.of("image", "scratch", "environment", expressions)));
        try {
            var output = Commands.capture(path.getParent(), List.of("docker", "compose", "--project-directory",
                    path.getParent().toString(), "--env-file", path.toString(), "-f", "-", "config", "--format", "json"),
                    JSON.writeValueAsString(definition), environment, Duration.ofSeconds(15));
            var values = JSON.readTree(output).get("services").get("values").get("environment");
            var result = new LinkedHashMap<String, String>();
            for (var key : KEYS) {
                var value = values.get(key);
                // Note: Compose의 canonical 출력은 달러를 이중화하므로 한 번만 복원한다.
                result.put(key, value == null || value.isNull() ? "" : value.asString().replace("$$", "$"));
            }
            return Map.copyOf(result);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("개인 환경변수를 해석하지 못했습니다. .env 형식과 Compose 설치를 확인하세요");
        }
    }

    public static boolean initialize(Path path) throws Exception {
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return false;
        var values = new LinkedHashMap<String, String>();
        var random = new SecureRandom();
        for (var key : KEYS) {
            var value = switch (key) {
                case "DB_USER" -> "iseonjwa";
                case "ADMIN_EMAIL" -> "admin@iseonjwa.local";
                case "JWT_ACCESS_SECRET", "JWT_REFRESH_SECRET" -> randomHex(random, 32);
                case "DB_PASSWORD" -> randomHex(random, 24);
                default -> randomHex(random, 16);
            };
            values.put(key, value);
        }
        var content = new StringBuilder();
        values.forEach((key, value) -> content.append(key).append('=').append(value).append('\n'));
        var permission = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"));
        try (var channel = Files.newByteChannel(path, Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE), permission)) {
            var buffer = ByteBuffer.wrap(content.toString().getBytes(StandardCharsets.UTF_8));
            while (buffer.hasRemaining()) channel.write(buffer);
            return true;
        } catch (FileAlreadyExistsException exception) {
            return false;
        }
    }

    private static String randomHex(SecureRandom random, int length) {
        var bytes = new byte[length];
        random.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
}
