package iseonjwa.verification;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigServerUnavailableTest {
    @TempDir
    Path temporary;

    @Test
    void 필수_설정_서버가_없으면_앱_기동이_실패한다() throws Exception {
        var root = WorkspaceClient.ROOT;
        var jar = root.resolve("eureka/build/libs/app.jar");
        assertTrue(Files.isRegularFile(jar), "기동 검증 전에 :eureka:bootJar를 실행하세요");
        var log = temporary.resolve("config-unavailable.log");
        try (var unavailable = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            var builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-Xmx128m", "-jar", jar.toString(),
                    "--spring.cloud.config.request-connect-timeout=200",
                    "--spring.cloud.config.request-read-timeout=200", "--server.port=0")
                    .directory(root.toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
            builder.environment().put("SPRING_PROFILES_ACTIVE", "local");
            builder.environment().put("CONFIG_SERVER_URL", "http://127.0.0.1:" + unavailable.getLocalPort());
            var process = builder.start();
            try {
                assertTrue(process.waitFor(45, TimeUnit.SECONDS), "기동 실패 확인 시간 초과");
                assertNotEquals(0, process.exitValue(), "설정 서버 없이 기동하면 안 된다");
                var output = Files.readString(log);
                assertTrue(output.contains("Could not locate PropertySource")
                        || output.contains("ConfigClientFailFastException"), "설정 서버 외의 원인으로 기동이 실패했다");
            } finally {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
        }
    }
}
