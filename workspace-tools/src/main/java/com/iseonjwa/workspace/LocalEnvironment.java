package com.iseonjwa.workspace;

import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.spec.ECGenParameterSpec;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public final class LocalEnvironment {
    public static final List<String> KEYS = List.of("DB_USER", "DB_PASSWORD", "JWT_ACCESS_SECRET",
            "JWT_REFRESH_SECRET", "PAYMENT_TOKEN_PRIVATE_KEY", "PAYMENT_TOKEN_PUBLIC_KEY", "ENTRY_TOKEN_SECRET", "BILLING_KEY_SECRET", "ADMIN_EMAIL",
            "ADMIN_PASSWORD", "GRAFANA_ADMIN_PASSWORD");
    // 결제 토큰 키 쌍(ES256, ADR 0054). 예매 서비스가 개인키로 서명하고 결제 서비스가 공개키로 검증한다. 한 쌍이라 따로 만들지 않는다
    private static final String PAYMENT_TOKEN_PRIVATE_KEY = "PAYMENT_TOKEN_PRIVATE_KEY";
    private static final String PAYMENT_TOKEN_PUBLIC_KEY = "PAYMENT_TOKEN_PUBLIC_KEY";
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
        var tokenKeys = generatePaymentTokenKeyPair();
        for (var key : KEYS) {
            values.put(key, generate(key, random, tokenKeys));
        }
        var content = new StringBuilder();
        values.forEach((key, value) -> content.append(key).append('=').append(value).append('\n'));
        try (var channel = Files.newByteChannel(path, Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE), ownerOnly(path))) {
            var buffer = ByteBuffer.wrap(content.toString().getBytes(StandardCharsets.UTF_8));
            while (buffer.hasRemaining()) channel.write(buffer);
            return true;
        } catch (FileAlreadyExistsException exception) {
            return false;
        }
    }

    // 기존 .env의 값은 그대로 두고, KEYS 중 아예 없는 키만 맨 끝에 추가한다. 추가한 키 이름만 돌려준다(값은 돌려주지 않는다).
    // Note: 값이 비어 있는 줄(KEY=)은 일부러 비운 것일 수 있어 건드리지 않는다. DB_PASSWORD처럼 이미 쓰인 값이 바뀌면
    // 로컬 Postgres 볼륨의 비밀번호와 어긋나므로 기존 줄은 다시 쓰지 않고 덧붙이기만 한다
    public static List<String> fillMissing(Path path) throws IOException {
        var content = Files.readString(path, StandardCharsets.UTF_8);
        var present = content.lines()
                .map(String::strip)
                .filter(line -> !line.isEmpty() && !line.startsWith("#") && line.contains("="))
                .map(line -> line.substring(0, line.indexOf('=')).strip())
                .collect(Collectors.toSet());

        // 키 쌍은 둘 다 없을 때만 만든다. 하나만 있으면 새로 만들지 않는다(짝이 맞지 않으면 모든 결제가 403이 된다)
        var hasPrivate = present.contains(PAYMENT_TOKEN_PRIVATE_KEY);
        var hasPublic = present.contains(PAYMENT_TOKEN_PUBLIC_KEY);
        if (hasPrivate != hasPublic) {
            throw new IllegalStateException((hasPrivate ? PAYMENT_TOKEN_PUBLIC_KEY : PAYMENT_TOKEN_PRIVATE_KEY)
                    + "가 .env에 없습니다. 결제 토큰 키는 한 쌍이라 하나만 있으면 새로 만들지 않습니다. "
                    + "두 줄을 모두 지우고 다시 실행하거나, 짝이 되는 키를 직접 넣으세요");
        }
        var random = new SecureRandom();
        var tokenKeys = hasPrivate ? null : generatePaymentTokenKeyPair();
        var added = new ArrayList<String>();
        var addition = new StringBuilder();
        for (var key : KEYS) {
            if (present.contains(key)) continue;
            addition.append(key).append('=').append(generate(key, random, tokenKeys)).append('\n');
            added.add(key);
        }
        if (added.isEmpty()) return List.of();

        // 마지막 줄에 줄바꿈이 없으면 새 키가 그 줄에 이어 붙지 않게 먼저 줄을 바꾼다
        if (!content.isEmpty() && !content.endsWith("\n")) addition.insert(0, '\n');
        Files.writeString(path, addition, StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        return List.copyOf(added);
    }

    private static String generate(String key, SecureRandom random, Map<String, String> tokenKeys) {
        return switch (key) {
            case "DB_USER" -> "iseonjwa";
            case "ADMIN_EMAIL" -> "admin@iseonjwa.local";
            case PAYMENT_TOKEN_PRIVATE_KEY, PAYMENT_TOKEN_PUBLIC_KEY -> tokenKeys.get(key);
            case "JWT_ACCESS_SECRET", "JWT_REFRESH_SECRET", "ENTRY_TOKEN_SECRET",
                 "BILLING_KEY_SECRET" -> randomHex(random, 32);
            case "DB_PASSWORD" -> randomHex(random, 24);
            default -> randomHex(random, 16);
        };
    }

    // P-256 키 쌍을 만든다. 개인키는 PKCS#8 DER, 공개키는 X.509 SubjectPublicKeyInfo DER이고 줄바꿈 없는 Base64 한 줄이다
    static Map<String, String> generatePaymentTokenKeyPair() {
        try {
            var generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"));
            var pair = generator.generateKeyPair();
            var encoder = Base64.getEncoder();
            return Map.of(PAYMENT_TOKEN_PRIVATE_KEY, encoder.encodeToString(pair.getPrivate().getEncoded()),
                    PAYMENT_TOKEN_PUBLIC_KEY, encoder.encodeToString(pair.getPublic().getEncoded()));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("결제 토큰 키 쌍을 만들지 못했습니다", exception);
        }
    }

    // Note: Windows(NTFS)는 POSIX 권한을 지원하지 않아 지정하면 예외가 난다. 이때는 상위 폴더의 접근 권한을 그대로 따른다
    static boolean supportsPosix(Path path) {
        return path.toAbsolutePath().getFileSystem().supportedFileAttributeViews().contains("posix");
    }

    static FileAttribute<?>[] ownerOnly(Path path) {
        if (!supportsPosix(path)) return new FileAttribute<?>[0];
        return new FileAttribute<?>[] {PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))};
    }

    private static String randomHex(SecureRandom random, int length) {
        var bytes = new byte[length];
        random.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
}
