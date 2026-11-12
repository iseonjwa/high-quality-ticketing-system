package com.iseonjwa.payment.pg.infrastructure;

import io.micrometer.core.instrument.binder.MeterBinder;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

// Resilience4j의 CB·Bulkhead 지표가 두 그룹 이름으로 등록되는지 설정 클래스를 그대로 써서 본다(ISE-156, ADR 0024 결정 3)
class PgProtectionMetricsTest {
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private Resilience4jPgApprovalGate approvalGate;

    @BeforeEach
    void setUp() {
        var configuration = new PgProtectionConfiguration();
        var breakers = configuration.pgCircuitBreakerRegistry();
        var bulkheads = configuration.pgBulkheadRegistry();
        var properties = new PgProtectionProperties(null, null, null);
        approvalGate = configuration.pgApprovalGate(properties, breakers, bulkheads);
        configuration.pgRecoveryGate(properties, breakers, bulkheads);
        configuration.pgBillingGate(properties, breakers, bulkheads);
        for (MeterBinder binder : new MeterBinder[] {configuration.pgCircuitBreakerMetrics(breakers),
                configuration.pgBulkheadMetrics(bulkheads)}) {
            binder.bindTo(meters);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"pg-approve", "pg-recovery", "pg-billing"})
    void CB_상태와_Bulkhead_여유가_그룹_이름으로_등록된다(String group) {
        assertNotNull(meters.find("resilience4j.circuitbreaker.state").tag("name", group).gauge(), group);
        assertNotNull(meters.find("resilience4j.circuitbreaker.failure.rate").tag("name", group).gauge(), group);
        assertNotNull(meters.find("resilience4j.bulkhead.available.concurrent.calls").tag("name", group).gauge(), group);
    }

    @Test
    void 지표는_그룹의_실제_Bulkhead를_읽는다() {
        var gauge = meters.get("resilience4j.bulkhead.available.concurrent.calls").tag("name", "pg-approve").gauge();

        assertEquals(approvalGate.bulkhead().getMetrics().getAvailableConcurrentCalls(), (int) gauge.value());
    }
}
