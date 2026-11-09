package com.iseonjwa.mockpg.fault;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 변조형 모드는 test 프로필에서만 설정할 수 있다(mock-pg.md 5절 프로필 제한). loadtest만 켜졌을 때 거절하고,
 * 거절한 설정은 규칙을 바꾸지 않아야 한다. 컨트롤러 빈을 프로필별 컨텍스트에서 꺼내 설정을 직접 건다.
 */
class FaultTamperingProfileTest {
    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withUserConfiguration(FaultPackageScan.class);

    @Configuration
    @ComponentScan(basePackageClasses = FaultInjector.class)
    static class FaultPackageScan {
    }

    @ParameterizedTest
    @EnumSource(value = FaultMode.class, names = {"BAD_REQUEST", "MISSING_AMOUNT", "WRONG_AMOUNT", "FRACTIONAL_AMOUNT"})
    void loadtest에서는_변조형_설정을_400으로_거절하고_규칙을_바꾸지_않는다(FaultMode mode) {
        runner.withInitializer(context -> context.getEnvironment().setActiveProfiles("loadtest"))
                .run(context -> {
                    var response = context.getBean(FaultController.class).apply(approveRule(mode));

                    assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
                    assertTrue(context.getBean(FaultInjector.class).currentRules().stream()
                            .allMatch(rule -> rule.mode() == FaultMode.NONE));
                });
    }

    @ParameterizedTest
    @EnumSource(value = FaultMode.class, names = {"BAD_REQUEST", "MISSING_AMOUNT", "WRONG_AMOUNT", "FRACTIONAL_AMOUNT"})
    void test에서는_변조형_설정을_받는다(FaultMode mode) {
        runner.withInitializer(context -> context.getEnvironment().setActiveProfiles("test"))
                .run(context -> {
                    var response = context.getBean(FaultController.class).apply(approveRule(mode));

                    assertEquals(HttpStatus.OK, response.getStatusCode());
                });
    }

    @Test
    void test와_loadtest를_함께_켜면_변조형도_받는다() {
        runner.withInitializer(context -> context.getEnvironment().setActiveProfiles("test", "loadtest"))
                .run(context -> assertEquals(HttpStatus.OK,
                        context.getBean(FaultController.class).apply(approveRule(FaultMode.WRONG_AMOUNT)).getStatusCode()));
    }

    @Test
    void loadtest에서도_운영형_모드는_받는다() {
        runner.withInitializer(context -> context.getEnvironment().setActiveProfiles("loadtest"))
                .run(context -> Arrays.stream(FaultMode.values())
                        .filter(mode -> !mode.tampering() && mode.appliesTo(FaultTarget.APPROVE) && mode != FaultMode.DELAY)
                        .forEach(mode -> assertEquals(HttpStatus.OK,
                                context.getBean(FaultController.class).apply(approveRule(mode)).getStatusCode(),
                                mode.name())));
    }

    @Test
    void 변조형은_위험_구분이_표와_같다() {
        var tampering = Arrays.stream(FaultMode.values()).filter(FaultMode::tampering).toList();

        assertEquals(java.util.List.of(FaultMode.BAD_REQUEST, FaultMode.MISSING_AMOUNT, FaultMode.WRONG_AMOUNT,
                FaultMode.FRACTIONAL_AMOUNT), tampering);
    }

    private static FaultRule approveRule(FaultMode mode) {
        return FaultRules.of(FaultTarget.APPROVE, mode, 1);
    }
}
