package com.iseonjwa.workspace;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalEnvironmentTest {
    @TempDir
    Path directory;

    @Test
    void 큰따옴표와_줄끝_주석을_같이_해석한다() throws Exception {
        assertEquals("review # = password", values("DB_PASSWORD=\"review # = password\" # comment\n", Map.of()).get("DB_PASSWORD"));
    }

    @Test
    void 작은따옴표의_보간_문자열을_그대로_유지한다() throws Exception {
        assertEquals("literal ${UNSET_VAR} # value",
                values("DB_PASSWORD='literal ${UNSET_VAR} # value'\n", Map.of()).get("DB_PASSWORD"));
    }

    @Test
    void 이스케이프와_따옴표_없는_주석을_해석한다() throws Exception {
        var result = values("DB_PASSWORD=\"line\\nvalue\"\nADMIN_EMAIL=admin@test.local # comment\n", Map.of());
        assertEquals("line\nvalue", result.get("DB_PASSWORD"));
        assertEquals("admin@test.local", result.get("ADMIN_EMAIL"));
    }

    @Test
    void 리터럴_달러의_개수를_보존한다() throws Exception {
        assertEquals("one$two$$end${UNSET_VAR}$",
                values("DB_PASSWORD='one$two$$end${UNSET_VAR}$'\n", Map.of()).get("DB_PASSWORD"));
    }

    @Test
    void 부모_환경변수가_파일보다_우선한다() throws Exception {
        assertEquals("parent value",
                values("DB_PASSWORD=\"file value\"\n", Map.of("DB_PASSWORD", "parent value")).get("DB_PASSWORD"));
    }

    @Test
    void 비밀값_파일을_0600으로_만들고_서명_키를_분리한다() throws Exception {
        var path = directory.resolve(".env");
        assertTrue(LocalEnvironment.initialize(path));
        if (LocalEnvironment.supportsPosix(path)) {
            assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(path)));
        }
        var result = LocalEnvironment.load(path, cleanEnvironment());
        assertTrue(result.get("JWT_ACCESS_SECRET").length() >= 32);
        assertTrue(result.get("JWT_REFRESH_SECRET").length() >= 32);
        assertFalse(result.get("JWT_ACCESS_SECRET").equals(result.get("JWT_REFRESH_SECRET")), "서명 키는 분리해야 한다");
    }

    @Test
    void 기존_파일을_덮어쓰지_않는다() throws Exception {
        var path = directory.resolve(".env");
        Files.writeString(path, "DB_PASSWORD=test-only-original\n");
        assertFalse(LocalEnvironment.initialize(path));
        assertEquals("DB_PASSWORD=test-only-original\n", Files.readString(path));
    }

    private Map<String, String> values(String content, Map<String, String> overrides) throws Exception {
        var path = directory.resolve(".env");
        Files.writeString(path, content);
        var environment = cleanEnvironment();
        environment.putAll(overrides);
        return LocalEnvironment.load(path, environment);
    }

    private Map<String, String> cleanEnvironment() {
        var environment = new HashMap<>(System.getenv());
        LocalEnvironment.KEYS.forEach(environment::remove);
        return environment;
    }
}
