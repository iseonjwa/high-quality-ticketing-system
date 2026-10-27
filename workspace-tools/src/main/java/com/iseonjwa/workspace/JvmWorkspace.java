package com.iseonjwa.workspace;

import tools.jackson.databind.json.JsonMapper;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

public final class JvmWorkspace implements AutoCloseable {
    private final Path root;
    private final List<Process> owned = new CopyOnWriteArrayList<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final JsonMapper json = JsonMapper.builder().build();
    private final Thread cleanup = new Thread(this::close, "jvm-workspace-cleanup");

    public JvmWorkspace(Path root) {
        this.root = root;
        Runtime.getRuntime().addShutdownHook(cleanup);
    }

    public void start() throws Exception {
        try {
            requireAvailablePorts();
            var values = LocalEnvironment.load(root.resolve(".env"));
            var logDirectory = root.resolve("build/jvm-security-check");
            Files.createDirectories(logDirectory);
            var processes = new LinkedHashMap<LocalApps.App, Process>();
            for (var app : LocalApps.ALL) {
                if (closed.get()) throw new IllegalStateException("JVM 검사가 종료되었습니다");
                var log = logDirectory.resolve(app.name() + ".log");
                if (!Files.exists(log)) Files.createFile(log, LocalEnvironment.ownerOnly(log));
                if (LocalEnvironment.supportsPosix(log)) {
                    Files.setPosixFilePermissions(log, PosixFilePermissions.fromString("rw-------"));
                }
                var process = LocalApps.process(root, app, values).redirectErrorStream(true).redirectOutput(log.toFile()).start();
                owned.add(process);
                if (closed.get()) {
                    Commands.stopOwned(List.of(process));
                    throw new IllegalStateException("JVM 검사가 종료되었습니다");
                }
                processes.put(app, process);
                if (Set.of("config-server", "eureka").contains(app.name())) waitReady(app, process);
            }
            for (var entry : processes.entrySet()) {
                waitReady(entry.getKey(), entry.getValue());
                checkBindings(entry.getKey(), entry.getValue());
            }
        } catch (Exception | Error failure) {
            close();
            throw failure;
        }
    }

    private void requireAvailablePorts() throws Exception {
        for (var app : LocalApps.ALL) {
            for (var port : new int[] {app.port(), app.managementPort()}) {
                try (var socket = new Socket()) {
                    try {
                        socket.connect(new InetSocketAddress("127.0.0.1", port), 200);
                    } catch (java.io.IOException unavailable) {
                        continue;
                    }
                    throw new IllegalStateException(port + " 사용 중. 이 프로젝트의 Compose 앱을 먼저 정지하세요");
                }
            }
        }
    }

    private void waitReady(LocalApps.App app, Process process) throws Exception {
        var deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos();
        while (System.nanoTime() < deadline) {
            if (!process.isAlive()) throw new IllegalStateException(app.name() + " 조기 종료. build/jvm-security-check 로그를 확인하세요");
            try {
                var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.managementPort()
                        + "/actuator/health/readiness")).timeout(Duration.ofSeconds(3)).build();
                var response = http.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200
                        && "UP".equals(json.readTree(response.body()).get("status").asString())) return;
            } catch (java.io.IOException | RuntimeException notReady) {
                // 서버가 열리는 동안의 연결 실패는 제한 시간 내에서 다시 확인한다.
            }
            Thread.sleep(500);
        }
        throw new IllegalStateException(app.name() + " readiness 제한 시간 초과");
    }

    private void checkBindings(LocalApps.App app, Process process) throws Exception {
        var output = Commands.capture(root, List.of("lsof", "-nP", "-a", "-p", Long.toString(process.pid()),
                "-iTCP", "-sTCP:LISTEN"), "", System.getenv(), Duration.ofSeconds(10));
        var addresses = new HashSet<String>();
        for (var line : output.lines().toList()) {
            var marker = line.indexOf("TCP ");
            if (marker >= 0) addresses.add(line.substring(marker + 4).split("\\s+")[0]);
        }
        var expected = Set.of("127.0.0.1:" + app.port(), "127.0.0.1:" + app.managementPort());
        if (!expected.equals(addresses)) throw new IllegalStateException(app.name() + " 실제 소켓이 loopback 규칙과 다릅니다");
        System.out.println("확인: " + app.name() + " 업무·관리 소켓 loopback");
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        Commands.stopOwned(List.copyOf(owned).reversed());
        http.close();
        if (Thread.currentThread() != cleanup) {
            try {
                Runtime.getRuntime().removeShutdownHook(cleanup);
            } catch (IllegalStateException ignored) {
                // 종료 중에는 등록한 훅이 정리를 마친다.
            }
        }
    }
}
