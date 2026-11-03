package com.iseonjwa.payment.pg.infrastructure;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 실제 mock-pg 앱(bootJar)을 test 프로필의 별도 JVM으로 띄운다. 어댑터 계약 테스트가 가짜 서버가 아니라
 * 실제 서버의 응답으로 돌게 하려는 것이다(mock-pg.md 1절).
 *
 * <p>Note: 같은 JVM에서 띄우지 않는 이유는 앱 모듈의 plain jar가 꺼져 있어 클래스패스에 넣을 수 없고, 두 앱의
 * application.yml이 클래스패스에서 충돌하기 때문이다(mock-pg.md 7절 #2).
 *
 * <p>Gradle로 실행하면 {@code :mock-pg:bootJar}를 먼저 만들고 jar 경로를 {@code mockPg.jar} 시스템 속성으로 넘긴다.
 * IDE에서 Gradle 없이 실행하면 저장소의 {@code mock-pg/build/libs/app.jar}를 쓰므로, mock-pg를 고친 뒤에는 jar를
 * 다시 만들어야 한다.
 */
final class MockPgProcess implements AutoCloseable {
    private static final Duration STARTUP_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(200);
    // 응답하지 않는 서버에 붙어도 기동 대기와 장애 설정이 끝나게 한다. JDK HttpClient는 기본 요청 시한이 없다
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(2);
    private static final int MAX_START_ATTEMPTS = 3;
    private static final int LOG_TAIL_LINES = 40;
    private static final String JAR_PROPERTY = "mockPg.jar";

    private final Process process;
    private final Path log;
    private final String baseUrl;
    private final Thread killOnExit;
    private final HttpClient http = HttpClient.newHttpClient();
    private final MockPgLedgerClient ledger;

    private MockPgProcess(Process process, Path log, String baseUrl) {
        this.process = process;
        this.log = log;
        this.baseUrl = baseUrl;
        this.ledger = new MockPgLedgerClient(baseUrl);
        // 테스트 JVM이 @AfterAll 없이 끝나도 자식 JVM이 포트를 잡은 채 남지 않게 한다
        this.killOnExit = new Thread(process::destroyForcibly);
        Runtime.getRuntime().addShutdownHook(killOnExit);
    }

    static MockPgProcess start() {
        var root = repositoryRoot();
        var jar = System.getProperty(JAR_PROPERTY) != null
                ? Path.of(System.getProperty(JAR_PROPERTY))
                : root.resolve("mock-pg/build/libs/app.jar");
        if (!Files.isRegularFile(jar)) {
            throw new IllegalStateException("mock-pg 실행 파일이 없습니다. 먼저 ./gradlew :mock-pg:bootJar를 실행하세요: " + jar);
        }
        var logDirectory = root.resolve("payment-service/build/mock-pg");
        for (int attempt = 1; ; attempt++) {
            var server = launch(jar, logDirectory);
            try {
                server.awaitReady();
                return server;
            } catch (PortTakenException exception) {
                // Note: 빈 포트를 고른 뒤 닫고 넘기므로 자식이 바인드하기 전에 다른 프로세스가 가져갈 수 있다
                server.close();
                if (attempt == MAX_START_ATTEMPTS) throw new IllegalStateException("mock-pg 포트를 얻지 못했습니다", exception);
            } catch (RuntimeException exception) {
                server.close();
                throw exception;
            }
        }
    }

    String baseUrl() {
        return baseUrl;
    }

    /** 이 서버의 원장 조회 클라이언트(mock-pg.md 9절). */
    MockPgLedgerClient ledger() {
        return ledger;
    }

    /**
     * 장애 주입 설정을 바꾼다(mock-pg.md 5절의 POST /mock-pg/faults). {@code count}가 null이면 해제할 때까지 유지한다.
     */
    void applyFault(String target, String mode, Integer count) {
        postFault("{\"target\":\"%s\",\"mode\":\"%s\",\"count\":%s}".formatted(target, mode, count));
    }

    /** 지연 장애를 건다. {@code count}가 null이면 해제할 때까지 유지한다. */
    void applyDelay(String target, long delayMs, Integer count) {
        postFault("{\"target\":\"%s\",\"mode\":\"DELAY\",\"delayMs\":%d,\"count\":%s}"
                .formatted(target, delayMs, count));
    }

    private void postFault(String json) {
        var request = HttpRequest.newBuilder(URI.create(baseUrl + "/mock-pg/faults"))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json)).build();
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException exception) {
            throw new IllegalStateException("장애 주입 설정 호출 실패", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("장애 주입 설정 중 인터럽트", exception);
        }
        if (response.statusCode() != 200) {
            throw new IllegalStateException("장애 주입 설정 실패 status=" + response.statusCode() + " body=" + response.body());
        }
    }

    @Override
    public void close() {
        http.close();
        process.destroy();
        try {
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly().waitFor(5, TimeUnit.SECONDS);
            }
        } catch (InterruptedException exception) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
        }
        try {
            Runtime.getRuntime().removeShutdownHook(killOnExit);
        } catch (IllegalStateException alreadyShuttingDown) {
            // JVM 종료 중에는 등록한 hook이 어차피 실행된다
        }
    }

    private static MockPgProcess launch(Path jar, Path logDirectory) {
        try {
            var port = freePort();
            var log = Files.createDirectories(logDirectory).resolve("mock-pg-" + port + ".log");
            var command = List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-Xmx256m", "-jar", jar.toString(),
                    "--spring.profiles.active=test", "--server.port=" + port);
            var process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
            return new MockPgProcess(process, log, "http://127.0.0.1:" + port);
        } catch (IOException exception) {
            throw new IllegalStateException("mock-pg를 띄우지 못했습니다", exception);
        }
    }

    private void awaitReady() {
        var health = HttpRequest.newBuilder(URI.create(baseUrl + "/actuator/health"))
                .timeout(REQUEST_TIMEOUT).GET().build();
        var deadline = Instant.now().plus(STARTUP_TIMEOUT);
        while (Instant.now().isBefore(deadline)) {
            if (!process.isAlive()) {
                var tail = logTail();
                if (tail.contains("already in use")) throw new PortTakenException("mock-pg 포트 충돌\n" + tail);
                throw failure("mock-pg가 기동 중 종료됐습니다");
            }
            try {
                var status = http.send(health, HttpResponse.BodyHandlers.discarding()).statusCode();
                // 같은 포트의 다른 앱이 200을 준 것을 자기 서버로 착각하지 않게 자식이 살아 있는지 함께 본다
                if (status == 200 && process.isAlive()) return;
            } catch (IOException notReadyYet) {
                // 아직 포트를 열지 않았거나 응답 시한이 지났다. 다시 묻는다
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("mock-pg 기동 대기 중 인터럽트", exception);
            }
            sleep(POLL_INTERVAL);
        }
        throw failure("mock-pg가 " + STARTUP_TIMEOUT.toSeconds() + "초 안에 준비되지 않았습니다");
    }

    // CI는 build 폴더를 남기지 않으므로 실패 원인을 예외 메시지에 함께 싣는다
    private IllegalStateException failure(String message) {
        return new IllegalStateException(message + ". 로그: " + log.toAbsolutePath() + "\n" + logTail());
    }

    private String logTail() {
        try {
            var lines = Files.readAllLines(log);
            return String.join("\n", lines.subList(Math.max(0, lines.size() - LOG_TAIL_LINES), lines.size()));
        } catch (IOException exception) {
            return "(로그를 읽지 못했다: " + exception.getMessage() + ")";
        }
    }

    // IDE마다 작업 경로가 모듈 폴더이거나 저장소 루트라서, settings.gradle이 있는 폴더까지 올라가 찾는다
    private static Path repositoryRoot() {
        for (var directory = Path.of("").toAbsolutePath(); directory != null; directory = directory.getParent()) {
            if (Files.isRegularFile(directory.resolve("settings.gradle"))) return directory;
        }
        throw new IllegalStateException("저장소 루트(settings.gradle)를 찾지 못했습니다: " + Path.of("").toAbsolutePath());
    }

    private static int freePort() throws IOException {
        try (var socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            return socket.getLocalPort();
        }
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private static final class PortTakenException extends RuntimeException {
        PortTakenException(String message) {
            super(message);
        }
    }
}
