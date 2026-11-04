package com.iseonjwa.payment.infrastructure.recovery;

import com.iseonjwa.payment.infrastructure.job.BackgroundJob;
import com.iseonjwa.payment.infrastructure.job.BackgroundJobScheduler;
import com.iseonjwa.payment.observability.PaymentMetrics;
import com.iseonjwa.payment.payment.application.PaymentRecheckService;
import com.iseonjwa.payment.refund.application.RefundWorkerService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * 복구 실행기({@code recovery}, 스레드 2개)에 올릴 작업을 등록한다(events-and-reliability.md 7절).
 * {@code enabled=false}인 작업은 등록하지 않는다. 스케줄러 빈은 항상 만들고, 등록된 작업이 하나도 없으면 스케줄러가 실행기를 만들지 않는다.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({PaymentRecheckProperties.class, RefundWorkerProperties.class})
public class RecoveryConfiguration {
    /** 복구 실행기에 올릴 작업을 가려내는 한정자. 정리 작업({@code housekeeping})과 같은 타입이라 구분이 필요하다 */
    public static final String QUALIFIER = "recovery";
    static final int THREADS = 2;

    @Bean
    @Qualifier(QUALIFIER)
    @ConditionalOnProperty(prefix = "payment.recheck", name = "enabled", havingValue = "true", matchIfMissing = true)
    BackgroundJob recheckJob(PaymentRecheckService recheck, PaymentRecheckProperties properties) {
        return new BackgroundJob("recheck", properties.interval(), properties.maxPerTick(), recheck::recheckNext);
    }

    @Bean
    @Qualifier(QUALIFIER)
    @ConditionalOnProperty(prefix = "payment.refund-worker", name = "enabled", havingValue = "true",
            matchIfMissing = true)
    BackgroundJob refundWorkerJob(RefundWorkerService worker, RefundWorkerProperties properties) {
        return new BackgroundJob("refund-worker", properties.interval(), properties.maxPerTick(), worker::processNext);
    }

    @Bean
    BackgroundJobScheduler recoveryScheduler(@Qualifier(QUALIFIER) ObjectProvider<BackgroundJob> jobs,
            PaymentMetrics metrics, @Qualifier("commonAuditingClock") Clock clock) {
        return new BackgroundJobScheduler("recovery", THREADS, jobs.orderedStream().toList(), metrics, clock);
    }
}
