package com.iseonjwa.payment.infrastructure.recovery;

import com.iseonjwa.payment.payment.application.PaymentRecheckService;
import com.iseonjwa.payment.refund.application.RefundWorkerService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 복구 실행기에 올릴 작업을 등록한다(events-and-reliability.md 7절). {@code enabled=false}인 작업은 등록하지 않는다.
 * 스케줄러 빈은 항상 만들고, 등록된 작업이 하나도 없으면 스케줄러가 실행기를 만들지 않는다.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({PaymentRecheckProperties.class, RefundWorkerProperties.class})
public class RecoveryConfiguration {
    @Bean
    @ConditionalOnProperty(prefix = "payment.recheck", name = "enabled", havingValue = "true", matchIfMissing = true)
    RecoveryJob recheckJob(PaymentRecheckService recheck, PaymentRecheckProperties properties) {
        return new RecoveryJob("recheck", properties.interval(), properties.maxPerTick(), recheck::recheckNext);
    }

    @Bean
    @ConditionalOnProperty(prefix = "payment.refund-worker", name = "enabled", havingValue = "true",
            matchIfMissing = true)
    RecoveryJob refundWorkerJob(RefundWorkerService worker, RefundWorkerProperties properties) {
        return new RecoveryJob("refund-worker", properties.interval(), properties.maxPerTick(), worker::processNext);
    }

    @Bean
    RecoveryScheduler recoveryScheduler(ObjectProvider<RecoveryJob> jobs) {
        return new RecoveryScheduler(jobs.orderedStream().toList());
    }
}
