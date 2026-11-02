package com.iseonjwa.payment.infrastructure.recovery;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * 환불 워커 설정(events-and-reliability.md 6·7절). 의미는 {@link PaymentRecheckProperties}와 같다.
 *
 * @param enabled    false면 환불 워커를 등록하지 않는다(테스트는 서비스 메서드를 직접 부른다)
 * @param interval   한 주기가 끝난 뒤 다음 주기까지의 간격
 * @param maxPerTick 한 주기에 처리하는 최대 건수
 */
@ConfigurationProperties(prefix = "payment.refund-worker")
public record RefundWorkerProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("1s") Duration interval,
        @DefaultValue("20") int maxPerTick) {
    public RefundWorkerProperties {
        if (interval.isZero() || interval.isNegative()) {
            throw new IllegalArgumentException("payment.refund-worker.interval은 0보다 커야 합니다");
        }
        if (maxPerTick < 1) {
            throw new IllegalArgumentException("payment.refund-worker.max-per-tick은 1 이상이어야 합니다");
        }
    }
}
