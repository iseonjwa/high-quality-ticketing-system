package com.iseonjwa.mockpg.fault;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 프로필별로 장애 주입 API 빈이 있는지 확인한다(mock-pg.md 5절). local·prod를 실제 앱으로 띄우면 설정 서버 import 때문에
 * 뜨지 않으므로 필요한 빈만 넣은 컨텍스트로 본다.
 */
class FaultProfileTest {
    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withUserConfiguration(FaultController.class, FaultInjector.class, ProfileGuard.class);

    @Test
    void local에서는_장애_주입_API가_없다() {
        runner.withInitializer(context -> context.getEnvironment().setActiveProfiles("local"))
                .run(context -> assertEquals(0, context.getBeansOfType(FaultController.class).size()));
    }

    @Test
    void 프로필이_없어도_장애_주입_API가_없다() {
        runner.run(context -> assertEquals(0, context.getBeansOfType(FaultController.class).size()));
    }

    @Test
    void prod에서는_장애_주입_API가_없다() {
        runner.withInitializer(context -> context.getEnvironment().setActiveProfiles("prod"))
                .run(context -> assertEquals(0, context.getBeansOfType(FaultController.class).size()));
    }

    @Test
    void test에서는_장애_주입_API가_있다() {
        runner.withInitializer(context -> context.getEnvironment().setActiveProfiles("test"))
                .run(context -> assertEquals(1, context.getBeansOfType(FaultController.class).size()));
    }

    @Test
    void loadtest에서는_장애_주입_API가_있다() {
        runner.withInitializer(context -> context.getEnvironment().setActiveProfiles("loadtest"))
                .run(context -> assertEquals(1, context.getBeansOfType(FaultController.class).size()));
    }

    @Test
    void prod와_loadtest를_함께_켜면_기동에_실패한다() {
        runner.withInitializer(context -> context.getEnvironment().setActiveProfiles("prod", "loadtest"))
                .run(context -> {
                    assertTrue(context.getStartupFailure() != null, "기동에 성공했다");
                    assertTrue(rootMessage(context.getStartupFailure()).contains("prod와 loadtest"),
                            rootMessage(context.getStartupFailure()));
                });
    }

    private static String rootMessage(Throwable failure) {
        var cause = failure;
        while (cause.getCause() != null) cause = cause.getCause();
        return String.valueOf(cause.getMessage());
    }
}
