package com.iseonjwa.workspace;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
        assertTrue(result.get("ENTRY_TOKEN_SECRET").length() >= 32);
        assertFalse(result.get("ENTRY_TOKEN_SECRET").equals(result.get("JWT_ACCESS_SECRET")), "서명 키는 분리해야 한다");
        assertTrue(result.get("BILLING_KEY_SECRET").matches("[0-9a-f]{64}"), "빌링키 암호화 키는 소문자 16진수 64자다");
        assertFalse(result.containsKey("PAYMENT_TOKEN_SECRET"), "결제 토큰은 더 이상 공유 비밀을 쓰지 않는다");
        assertPaymentTokenKeysArePair(result.get("PAYMENT_TOKEN_PRIVATE_KEY"), result.get("PAYMENT_TOKEN_PUBLIC_KEY"));
    }

    @Test
    void 결제_토큰_키_쌍이_모두_없으면_한_쌍을_만들어_추가한다() throws Exception {
        var path = directory.resolve(".env");
        Files.writeString(path, "DB_PASSWORD=test-only-original\nPAYMENT_TOKEN_SECRET=old-value-kept\n");

        var added = LocalEnvironment.fillMissing(path);

        assertTrue(added.contains("PAYMENT_TOKEN_PRIVATE_KEY") && added.contains("PAYMENT_TOKEN_PUBLIC_KEY"));
        assertFalse(added.contains("PAYMENT_TOKEN_SECRET"));
        var content = Files.readString(path);
        assertTrue(content.contains("PAYMENT_TOKEN_SECRET=old-value-kept\n"), "이미 있는 줄은 그대로 둔다");
        var result = LocalEnvironment.load(path, cleanEnvironment());
        assertPaymentTokenKeysArePair(result.get("PAYMENT_TOKEN_PRIVATE_KEY"), result.get("PAYMENT_TOKEN_PUBLIC_KEY"));
    }

    @Test
    void 결제_토큰_키가_하나만_있으면_새로_만들지_않고_없는_키를_알리며_실패한다() throws Exception {
        for (var present : List.of("PAYMENT_TOKEN_PRIVATE_KEY", "PAYMENT_TOKEN_PUBLIC_KEY")) {
            var path = directory.resolve(present + ".env");
            var original = "DB_PASSWORD=test-only-original\n" + present + "=test-only-key-value\n";
            Files.writeString(path, original);

            var exception = assertThrows(IllegalStateException.class, () -> LocalEnvironment.fillMissing(path));

            var missing = present.equals("PAYMENT_TOKEN_PRIVATE_KEY") ? "PAYMENT_TOKEN_PUBLIC_KEY" : "PAYMENT_TOKEN_PRIVATE_KEY";
            assertTrue(exception.getMessage().startsWith(missing), exception.getMessage());
            assertFalse(exception.getMessage().contains("test-only-key-value"));
            assertEquals(original, Files.readString(path), "실패하면 파일을 바꾸지 않는다");
        }
    }

    // 개인키(PKCS#8)로 서명한 것을 공개키(X.509)로 검증할 수 있어야 한 쌍이다. 값은 줄바꿈 없는 Base64 한 줄이다
    private static void assertPaymentTokenKeysArePair(String privateKey, String publicKey) throws Exception {
        assertTrue(privateKey.matches("[A-Za-z0-9+/]+=*"), "Base64 한 줄이어야 한다");
        assertTrue(publicKey.matches("[A-Za-z0-9+/]+=*"), "Base64 한 줄이어야 한다");
        var factory = java.security.KeyFactory.getInstance("EC");
        var decoder = java.util.Base64.getDecoder();
        var priv = factory.generatePrivate(new java.security.spec.PKCS8EncodedKeySpec(decoder.decode(privateKey)));
        var pub = factory.generatePublic(new java.security.spec.X509EncodedKeySpec(decoder.decode(publicKey)));
        var signature = java.security.Signature.getInstance("SHA256withECDSA");
        signature.initSign(priv);
        signature.update("pair".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var signed = signature.sign();
        signature.initVerify(pub);
        signature.update("pair".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertTrue(signature.verify(signed), "개인키로 서명한 것이 공개키로 검증돼야 한다");
    }

    @Test
    void 기존_파일을_덮어쓰지_않는다() throws Exception {
        var path = directory.resolve(".env");
        Files.writeString(path, "DB_PASSWORD=test-only-original\n");
        assertFalse(LocalEnvironment.initialize(path));
        assertEquals("DB_PASSWORD=test-only-original\n", Files.readString(path));
    }

    @Test
    void 빠진_키만_추가하고_기존_값은_그대로_둔다() throws Exception {
        var path = directory.resolve(".env");
        Files.writeString(path, "DB_PASSWORD=test-only-original\n");

        var added = LocalEnvironment.fillMissing(path);

        assertTrue(added.contains("ENTRY_TOKEN_SECRET"));
        assertFalse(added.contains("DB_PASSWORD"));
        var content = Files.readString(path);
        assertTrue(content.startsWith("DB_PASSWORD=test-only-original\n"));
        assertTrue(content.lines().anyMatch(line -> line.matches("ENTRY_TOKEN_SECRET=[0-9a-f]{64}")));
    }

    @Test
    void 모든_키가_있으면_파일을_바꾸지_않는다() throws Exception {
        var path = directory.resolve(".env");
        assertTrue(LocalEnvironment.initialize(path));
        var before = Files.readString(path);

        assertTrue(LocalEnvironment.fillMissing(path).isEmpty());
        assertEquals(before, Files.readString(path));
    }

    @Test
    void 마지막_줄에_줄바꿈이_없어도_이어_붙이지_않는다() throws Exception {
        var path = directory.resolve(".env");
        Files.writeString(path, "DB_PASSWORD=test-only-original");

        LocalEnvironment.fillMissing(path);

        assertTrue(Files.readString(path).startsWith("DB_PASSWORD=test-only-original\n"));
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
