package com.iseonjwa.payment.pg.infrastructure;

import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.micrometer.tagged.TaggedBulkheadMetrics;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * PG 호출 세 그룹(승인·복구·자동결제)의 보호 장치. 그룹마다 따로 Bulkhead와 CB를 둔다(0018, 자동결제 분리는 ADR 0051).
 *
 * <p>Note: CB와 Bulkhead 지표는 직접 만들지 않고 Resilience4j가 주는 {@code Tagged*Metrics}를 그대로 쓴다. 세 그룹은 이 클래스의
 * 레지스트리에 등록되고 지표는 그룹 이름({@code name} 태그)으로 나뉜다. {@link MeterBinder} 빈은 Boot가 지표 레지스트리에 묶는다.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PgProtectionProperties.class)
public class PgProtectionConfiguration {
    @Bean
    CircuitBreakerRegistry pgCircuitBreakerRegistry() {
        return CircuitBreakerRegistry.ofDefaults();
    }

    @Bean
    BulkheadRegistry pgBulkheadRegistry() {
        return BulkheadRegistry.ofDefaults();
    }

    @Bean
    MeterBinder pgCircuitBreakerMetrics(CircuitBreakerRegistry registry) {
        return TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(registry);
    }

    @Bean
    MeterBinder pgBulkheadMetrics(BulkheadRegistry registry) {
        return TaggedBulkheadMetrics.ofBulkheadRegistry(registry);
    }

    @Bean
    Resilience4jPgApprovalGate pgApprovalGate(PgProtectionProperties properties, CircuitBreakerRegistry breakers,
            BulkheadRegistry bulkheads) {
        return new Resilience4jPgApprovalGate(new Resilience4jPgGroup("pg-approve", properties.approve(), breakers,
                bulkheads));
    }

    @Bean
    Resilience4jPgBillingGate pgBillingGate(PgProtectionProperties properties, CircuitBreakerRegistry breakers,
            BulkheadRegistry bulkheads) {
        return new Resilience4jPgBillingGate(new Resilience4jPgGroup("pg-billing", properties.billing(), breakers,
                bulkheads));
    }

    @Bean
    Resilience4jPgRecoveryGate pgRecoveryGate(PgProtectionProperties properties, CircuitBreakerRegistry breakers,
            BulkheadRegistry bulkheads) {
        return new Resilience4jPgRecoveryGate(new Resilience4jPgGroup("pg-recovery", properties.recovery(), breakers,
                bulkheads));
    }
}
