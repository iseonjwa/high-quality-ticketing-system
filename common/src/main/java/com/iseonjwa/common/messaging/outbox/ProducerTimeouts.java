package com.iseonjwa.common.messaging.outbox;

import java.time.Duration;
import java.util.Map;

/**
 * 릴레이의 임대 계산이 기대는 Kafka 프로듀서 시한을 확인한다. 프로듀서가 {@code max.block.ms + delivery.timeout.ms}보다
 * 오래 붙잡으면 한 번의 실행이 임대를 넘겨, 다른 릴레이가 같은 행을 다시 보내 순서가 뒤섞인 중복이 생긴다.
 */
final class ProducerTimeouts {
    // Kafka 기본값. 설정에 없으면 이 값이 적용된다
    private static final long DEFAULT_MAX_BLOCK_MS = 60_000;
    private static final long DEFAULT_DELIVERY_TIMEOUT_MS = 120_000;

    private ProducerTimeouts() {}

    static void requireWithin(Map<String, Object> producerConfig, Duration sendTimeout) {
        var maxBlock = millis(producerConfig, "max.block.ms", DEFAULT_MAX_BLOCK_MS);
        var delivery = millis(producerConfig, "delivery.timeout.ms", DEFAULT_DELIVERY_TIMEOUT_MS);
        if (maxBlock + delivery > sendTimeout.toMillis()) {
            throw new IllegalStateException("프로듀서 max.block.ms(" + maxBlock + ") + delivery.timeout.ms(" + delivery
                    + ")가 messaging.outbox.send-timeout(" + sendTimeout.toMillis()
                    + "ms)을 넘는다. 프로듀서 시한을 줄이거나 send-timeout과 lease를 늘려야 한다");
        }
    }

    private static long millis(Map<String, Object> config, String key, long defaultValue) {
        var value = config.get(key);
        return value == null ? defaultValue : Long.parseLong(String.valueOf(value));
    }
}
