package com.iseonjwa.payment.global;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaymentTokenConfigurationTest {
    private static final String VALID = "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(PaymentTokenConfiguration.class);

    @Test
    void 올바른_키가_있으면_빈을_만든다() {
        runner.withPropertyValues("payment.token.secret=" + VALID)
                .run(context -> assertNotNull(context.getBean(PaymentTokenKey.class)));
    }

    @Test
    void 키_설정이_없으면_컨텍스트가_뜨지_않는다() {
        runner.run(context -> assertTrue(context.getStartupFailure() != null));
    }

    @Test
    void 형식이_틀리면_컨텍스트가_뜨지_않고_실패_원인에_키_값이_없다() {
        var badKey = VALID.toUpperCase();

        runner.withPropertyValues("payment.token.secret=" + badKey).run(context -> {
            var failure = context.getStartupFailure();
            assertNotNull(failure);
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                assertFalse(String.valueOf(cause.getMessage()).contains(badKey), "예외 메시지에 키가 노출됐다");
            }
        });
    }
}
