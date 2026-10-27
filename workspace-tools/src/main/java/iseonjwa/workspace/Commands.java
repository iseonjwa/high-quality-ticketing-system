package iseonjwa.workspace;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class Commands {
    private Commands() {}

    public static String capture(Path directory, List<String> arguments, String input,
            Map<String, String> environment, Duration timeout) throws Exception {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var builder = new ProcessBuilder(arguments).directory(directory.toFile())
                    .redirectError(ProcessBuilder.Redirect.DISCARD);
            builder.environment().clear();
            builder.environment().putAll(environment);
            var process = builder.start();
            try {
                var output = executor.submit(() -> process.getInputStream().readAllBytes());
                process.getOutputStream().write(input.getBytes(StandardCharsets.UTF_8));
                process.getOutputStream().close();
                if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                    throw new IllegalStateException("외부 명령 시간 초과: " + arguments.getFirst());
                }
                if (process.exitValue() != 0) {
                    throw new IllegalStateException("외부 명령 실패: " + arguments.getFirst());
                }
                return new String(output.get(2, TimeUnit.SECONDS), StandardCharsets.UTF_8);
            } finally {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                if (!process.waitFor(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("외부 명령의 종료를 확인하지 못했습니다");
                }
            }
        }
    }

    public static void stopOwned(Collection<Process> processes) {
        for (var process : processes) {
            process.descendants().forEach(ProcessHandle::destroy);
            process.destroy();
        }
        var deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        for (var process : processes) {
            try {
                var remaining = Math.max(0, deadline - System.nanoTime());
                if (!process.waitFor(remaining, TimeUnit.NANOSECONDS)) {
                    process.descendants().forEach(ProcessHandle::destroyForcibly);
                    process.destroyForcibly();
                    process.waitFor(5, TimeUnit.SECONDS);
                }
            } catch (InterruptedException exception) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                Thread.currentThread().interrupt();
            }
        }
    }
}
