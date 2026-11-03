package com.iseonjwa.mockpg.ledger;

import com.iseonjwa.mockpg.fault.FaultInjector;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 프로필별로 원장 조회 API 빈이 있는지 확인한다(mock-pg.md 5절 프로필 제한). local·prod를 실제 앱으로 띄우면 설정 서버
 * import 때문에 뜨지 않으므로 ledger·fault 패키지만 훑는 컨텍스트로 본다. 빈이 없으면 요청은 404다.
 */
class LedgerProfileTest {
    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withUserConfiguration(PackageScan.class);

    @Configuration
    @ComponentScan(basePackageClasses = {LedgerHolder.class, FaultInjector.class})
    static class PackageScan {
    }

    @Test
    void local에서는_원장_조회_API가_없다() {
        runner.withInitializer(context -> context.getEnvironment().setActiveProfiles("local"))
                .run(context -> assertEquals(0, context.getBeansOfType(LedgerController.class).size()));
    }

    @Test
    void 프로필이_없어도_원장_조회_API가_없다() {
        runner.run(context -> assertEquals(0, context.getBeansOfType(LedgerController.class).size()));
    }

    @Test
    void prod에서는_원장_조회_API가_없다() {
        runner.withInitializer(context -> context.getEnvironment().setActiveProfiles("prod"))
                .run(context -> assertEquals(0, context.getBeansOfType(LedgerController.class).size()));
    }

    @Test
    void prod와_test를_함께_켜도_원장_조회_API가_없다() {
        runner.withInitializer(context -> context.getEnvironment().setActiveProfiles("prod", "test"))
                .run(context -> assertEquals(0, context.getBeansOfType(LedgerController.class).size()));
    }

    @Test
    void test에서는_원장_조회_API가_있다() {
        runner.withInitializer(context -> context.getEnvironment().setActiveProfiles("test"))
                .run(context -> assertEquals(1, context.getBeansOfType(LedgerController.class).size()));
    }

    @Test
    void loadtest에서는_원장_조회_API가_있다() {
        runner.withInitializer(context -> context.getEnvironment().setActiveProfiles("loadtest"))
                .run(context -> assertEquals(1, context.getBeansOfType(LedgerController.class).size()));
    }
}
