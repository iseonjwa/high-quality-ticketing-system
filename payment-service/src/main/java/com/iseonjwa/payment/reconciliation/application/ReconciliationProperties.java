package com.iseonjwa.payment.reconciliation.application;

import com.iseonjwa.payment.reconciliation.domain.ReconciliationPolicy;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * 대사 작업 설정(ADR 0029, events-and-reliability.md 10절). 기본값은 모두 {@code [가정]}이다.
 *
 * @param enabled     false면 작업을 등록하지 않는다. 테스트는 서비스의 한 회차 실행 메서드를 직접 부른다
 * @param interval    한 회차가 끝난 뒤 다음 회차까지의 간격
 * @param allowedLag  허용 지연 L. 마지막 갱신이 이보다 가까운 대상은 판단하지 않는다
 * @param window      결제를 고르는 기간(마지막 갱신 기준)
 * @param eventWindow 확정 전이와 Outbox 행을 대조하는 기간(RC-8)
 */
@ConfigurationProperties(prefix = "payment.reconciliation")
public record ReconciliationProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("5m") Duration interval,
        @DefaultValue("10m") Duration allowedLag,
        @DefaultValue("7d") Duration window,
        @DefaultValue("24h") Duration eventWindow) {
    public ReconciliationProperties {
        requirePositive(interval, "interval");
        requirePositive(allowedLag, "allowed-lag");
        requirePositive(window, "window");
        requirePositive(eventWindow, "event-window");
    }

    public ReconciliationPolicy policy() {
        return new ReconciliationPolicy(allowedLag, window, eventWindow);
    }

    private static void requirePositive(Duration value, String name) {
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException("payment.reconciliation." + name + "은 0보다 커야 합니다");
        }
    }
}
