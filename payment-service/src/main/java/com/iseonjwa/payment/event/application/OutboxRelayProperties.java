package com.iseonjwa.payment.event.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * @param lease 집은 행을 다른 실행이 다시 집지 못하는 시간. 프로듀서 delivery.timeout.ms의 2배 이상이어야 한다.
 */
@ConfigurationProperties(prefix = "payment.outbox")
public record OutboxRelayProperties(Boolean relayEnabled, Duration interval, Integer batchSize, Duration lease) {
    public OutboxRelayProperties {
        relayEnabled = relayEnabled == null || relayEnabled;
        interval = interval != null ? interval : Duration.ofMillis(500);
        batchSize = batchSize != null ? batchSize : 20;
        lease = lease != null ? lease : Duration.ofSeconds(30);
    }
}
