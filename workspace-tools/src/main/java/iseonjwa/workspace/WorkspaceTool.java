package iseonjwa.workspace;

import java.nio.file.Path;
import java.util.List;

public final class WorkspaceTool {
    private WorkspaceTool() {}

    public static void main(String[] args) throws Exception {
        if (args.length == 0) throw new IllegalArgumentException("실행할 작업을 지정하세요");
        var root = WorkspacePaths.root();
        switch (args[0]) {
            case "init-env" -> System.out.println(LocalEnvironment.initialize(root.resolve(".env"))
                    ? "개인 개발용 .env 생성 (POSIX면 0600, 값은 출력하지 않음)" : ".env가 있어 기존 값을 유지한다");
            case "check-ports" -> {
                PortExposure.check(root);
                System.out.println("포트 공개 검사 통과: 기본 Compose와 local override의 공개 규칙을 확인했다");
            }
            case "run-local" -> run(root, LocalApps.named(args[1]));
            default -> throw new IllegalArgumentException("지원하지 않는 작업입니다");
        }
    }

    private static void run(Path root, LocalApps.App app) throws Exception {
        var values = LocalEnvironment.load(root.resolve(".env"));
        var process = LocalApps.process(root, app, values).inheritIO().start();
        var cleanup = new Thread(() -> Commands.stopOwned(List.of(process)), "local-app-cleanup");
        Runtime.getRuntime().addShutdownHook(cleanup);
        try {
            System.out.println(app.name() + " loopback 실행 (개인 환경변수 값은 출력하지 않는다)");
            if (process.waitFor() != 0) throw new IllegalStateException(app.name() + " 실행이 실패했습니다");
        } finally {
            Commands.stopOwned(List.of(process));
            try {
                Runtime.getRuntime().removeShutdownHook(cleanup);
            } catch (IllegalStateException ignored) {
                // JVM 종료 중에는 등록한 종료 훅이 정리를 맡는다.
            }
        }
    }
}
