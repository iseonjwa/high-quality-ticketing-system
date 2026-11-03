package com.iseonjwa.payment.reconciliation.presentation.scheduling;

import com.iseonjwa.payment.infrastructure.housekeeping.HousekeepingConfiguration;
import com.iseonjwa.payment.infrastructure.housekeeping.HousekeepingProperties;
import com.iseonjwa.payment.infrastructure.job.BackgroundJob;
import com.iseonjwa.payment.reconciliation.application.ReconciliationProperties;
import com.iseonjwa.payment.reconciliation.application.ReconciliationService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * {@code reconciliation} 작업을 {@code housekeeping} 실행기에 올린다(ADR 0029, events-and-reliability.md 7·10절). 입구
 * 어댑터라 presentation에 둔다(ADR 0043). 설정은 {@code enabled}와 상관없이 읽어 검증하고, 꺼 두면 작업만 등록하지 않는다.
 *
 * <p>Note: 같은 실행기(스레드 1개)에서 도는 {@code gauge-refresh}와 정리 작업은 한 회차가 끝나야 돈다. 대사 한 회차가
 * 길어지면 그만큼 Gauge 갱신이 밀리고, 그래서 {@code gauge-refresh} 정체 알림이 먼저 울릴 수 있다. 예매 서비스 호출이 붙는
 * RC-1~RC-6(ISE-168)에서는 호출 시한을 짧게 두어 한 회차를 묶는다.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ReconciliationProperties.class)
public class ReconciliationJobConfiguration {
    public static final String JOB_NAME = "reconciliation";

    /**
     * 기동 때 {@code event-window + allowed-lag < SENT Outbox 보관 기간}을 검증한다. 보관 정리가 대사 판단 범위 안의 Outbox를
     * 지우면 정상 전이가 이벤트 누락으로 보인다. 보관 기간은 {@code payment.housekeeping.outbox-sent-retention}이다.
     */
    @Bean
    ReconciliationRetentionGuard reconciliationRetentionGuard(ReconciliationProperties reconciliation,
            HousekeepingProperties housekeeping) {
        var reach = reconciliation.eventWindow().plus(reconciliation.allowedLag());
        if (reach.compareTo(housekeeping.outboxSentRetention()) >= 0) {
            throw new IllegalArgumentException("payment.reconciliation.event-window(" + reconciliation.eventWindow()
                    + ") + allowed-lag(" + reconciliation.allowedLag() + ")은 payment.housekeeping.outbox-sent-retention("
                    + housekeeping.outboxSentRetention() + ")보다 짧아야 합니다");
        }
        return new ReconciliationRetentionGuard();
    }

    /** 검증을 통과했다는 표지 빈. */
    static final class ReconciliationRetentionGuard {}

    @Bean
    @Qualifier(HousekeepingConfiguration.QUALIFIER)
    @ConditionalOnProperty(prefix = "payment.reconciliation", name = "enabled", havingValue = "true",
            matchIfMissing = true)
    BackgroundJob reconciliationJob(ReconciliationService service, ReconciliationProperties properties,
            @Qualifier("commonAuditingClock") Clock clock) {
        // 한 주기에 한 회차를 돌고 끝낸다(false)
        return new BackgroundJob(JOB_NAME, properties.interval(), 1, () -> {
            service.runOnce(clock.instant());
            return false;
        });
    }
}
