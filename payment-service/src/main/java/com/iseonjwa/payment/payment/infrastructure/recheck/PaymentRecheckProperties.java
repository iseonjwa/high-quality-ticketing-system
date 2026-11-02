package com.iseonjwa.payment.payment.infrastructure.recheck;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * 결제 재확인 스케줄러 설정(events-and-reliability.md 6·7절).
 *
 * @param enabled    false면 스케줄러를 시작하지 않는다(테스트는 서비스 메서드를 직접 부른다)
 * @param interval   한 주기가 끝난 뒤 다음 주기까지의 간격
 * @param maxPerTick 한 주기에 처리하는 최대 건수. 이 만큼 처리하면 남은 건은 다음 주기로 넘긴다
 */
@ConfigurationProperties(prefix = "payment.recheck")
public record PaymentRecheckProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("1s") Duration interval,
        @DefaultValue("20") int maxPerTick) {
    public PaymentRecheckProperties {
        if (interval.isZero() || interval.isNegative()) {
            throw new IllegalArgumentException("payment.recheck.interval은 0보다 커야 합니다");
        }
        if (maxPerTick < 1) {
            throw new IllegalArgumentException("payment.recheck.max-per-tick은 1 이상이어야 합니다");
        }
    }
}
