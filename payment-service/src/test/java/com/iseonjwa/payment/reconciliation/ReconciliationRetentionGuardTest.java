package com.iseonjwa.payment.reconciliation;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import com.iseonjwa.payment.infrastructure.housekeeping.HousekeepingProperties;
import com.iseonjwa.payment.reconciliation.application.ReconciliationProperties;
import com.iseonjwa.payment.reconciliation.presentation.scheduling.ReconciliationJobConfiguration;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 대사의 판단 범위(event-window + allowed-lag)가 SENT Outbox 보관 기간 이상이면 기동에 실패한다. */
class ReconciliationRetentionGuardTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesOnly.class, ReconciliationJobConfiguration.class);

    @Configuration
    @EnableConfigurationProperties(HousekeepingProperties.class)
    static class PropertiesOnly {}

    @Test
    void 기본값은_기동에_성공한다() {
        runner.withPropertyValues("payment.reconciliation.enabled=false").run(context -> {
            assertNotNull(context);
            assertTrue(context.getStartupFailure() == null, String.valueOf(context.getStartupFailure()));
        });
    }

    @Test
    void 판단_범위가_보관_기간_이상이면_기동에_실패한다() {
        runner.withPropertyValues("payment.reconciliation.enabled=false", "payment.reconciliation.event-window=7d")
                .run(context -> {
                    var failure = context.getStartupFailure();
                    assertNotNull(failure);
                    assertTrue(rootMessage(failure).contains("outbox-sent-retention"), rootMessage(failure));
                });
    }

    private static String rootMessage(Throwable throwable) {
        var cause = throwable;
        while (cause.getCause() != null) cause = cause.getCause();
        return String.valueOf(cause.getMessage());
    }
}
