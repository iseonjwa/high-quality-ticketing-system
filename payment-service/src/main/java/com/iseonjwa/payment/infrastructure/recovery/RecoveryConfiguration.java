package com.iseonjwa.payment.infrastructure.recovery;

import com.iseonjwa.payment.payment.application.PaymentRecheckService;
import com.iseonjwa.payment.refund.application.RefundWorkerService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * 복구 실행기에 올릴 작업을 등록한다(events-and-reliability.md 7절). {@code enabled=false}인 작업은 등록하지 않고,
 * 등록된 작업이 하나도 없으면 스케줄러도 만들지 않는다.
 *
 * <p>Note: 스케줄러 빈 메서드는 작업 빈 메서드보다 아래에 둔다. {@code @ConditionalOnBean}은 같은 설정 클래스 안에서
 * 선언 순서대로 평가되기 때문이다.
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
    @ConditionalOnBean(RecoveryJob.class)
    RecoveryScheduler recoveryScheduler(List<RecoveryJob> jobs) {
        return new RecoveryScheduler(jobs);
    }
}
