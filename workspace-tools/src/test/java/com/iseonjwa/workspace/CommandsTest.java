package com.iseonjwa.workspace;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandsTest {
    @TempDir
    Path directory;

    @Test
    void 명령_제한_시간이_지나면_자식_JVM을_남기지_않는다() throws Exception {
        var marker = directory.resolve("timed-out.pid");
        var failure = assertThrows(IllegalStateException.class,
                () -> Commands.capture(directory, command(marker), "", System.getenv(), Duration.ofSeconds(3)));
        assertTrue(failure.getMessage().contains("시간 초과"));
        assertTrue(Files.exists(marker), "자식 JVM이 실행되어야 한다");
        var pid = Long.parseLong(Files.readString(marker));
        assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false), "시간 초과 후 JVM이 남아 있다");
    }

    @Test
    void 소유한_프로세스를_종료하고_종료를_기다린다() throws Exception {
        var marker = directory.resolve("owned.pid");
        var process = new ProcessBuilder(command(marker)).redirectError(ProcessBuilder.Redirect.DISCARD)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        try {
            var deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (!Files.exists(marker) && process.isAlive() && System.nanoTime() < deadline) Thread.sleep(20);
            assertTrue(Files.exists(marker), "자식 JVM 준비 실패");
            Commands.stopOwned(List.of(process));
            assertFalse(process.isAlive());
        } finally {
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
        }
    }

    private List<String> command(Path marker) throws Exception {
        var classes = Path.of(Child.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        return List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", classes.toString(), Child.class.getName(), marker.toString());
    }

    public static class Child {
        public static void main(String[] args) throws Exception {
            Files.writeString(Path.of(args[0]), Long.toString(ProcessHandle.current().pid()));
            Thread.sleep(60_000);
        }
    }
}
