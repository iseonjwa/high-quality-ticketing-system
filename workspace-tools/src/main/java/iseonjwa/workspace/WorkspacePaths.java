package iseonjwa.workspace;

import java.nio.file.Files;
import java.nio.file.Path;

public final class WorkspacePaths {
    private WorkspacePaths() {}

    public static Path root() {
        var configured = System.getProperty("workspace.root");
        if (configured != null) return Path.of(configured).toAbsolutePath().normalize();
        for (var path = Path.of("").toAbsolutePath(); path != null; path = path.getParent()) {
            if (Files.isRegularFile(path.resolve("settings.gradle"))
                    && Files.isDirectory(path.resolve("workspace-tools"))) return path;
        }
        throw new IllegalStateException("저장소 루트를 찾지 못했습니다. workspace.root를 지정하세요");
    }
}
