package com.iseonjwa.payment.pg.infrastructure;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * PG 호출 두 그룹의 보호 장치. 그룹마다 따로 Bulkhead와 CB를 둔다(0018).
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PgProtectionProperties.class)
public class PgProtectionConfiguration {
    @Bean
    Resilience4jPgApprovalGate pgApprovalGate(PgProtectionProperties properties) {
        return new Resilience4jPgApprovalGate(new Resilience4jPgGroup("pg-approve", properties.approve()));
    }

    @Bean
    Resilience4jPgRecoveryGate pgRecoveryGate(PgProtectionProperties properties) {
        return new Resilience4jPgRecoveryGate(new Resilience4jPgGroup("pg-recovery", properties.recovery()));
    }
}
