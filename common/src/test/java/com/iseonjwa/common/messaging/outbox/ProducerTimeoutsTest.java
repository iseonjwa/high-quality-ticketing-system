package com.iseonjwa.common.messaging.outbox;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProducerTimeoutsTest {
    private static final Duration SEND_TIMEOUT = Duration.ofSeconds(12);

    @Test
    void 프로듀서_시한_합이_전송_시한_안이면_통과한다() {
        assertDoesNotThrow(() -> ProducerTimeouts.requireWithin(
                Map.of("max.block.ms", 2000, "delivery.timeout.ms", "10000"), SEND_TIMEOUT));
    }

    @Test
    void 프로듀서_시한_합이_전송_시한을_넘으면_기동을_막는다() {
        assertThrows(IllegalStateException.class, () -> ProducerTimeouts.requireWithin(
                Map.of("max.block.ms", 2000, "delivery.timeout.ms", 11000), SEND_TIMEOUT));
    }

    @Test
    void 설정이_없으면_Kafka_기본값으로_보고_막는다() {
        assertThrows(IllegalStateException.class, () -> ProducerTimeouts.requireWithin(Map.of(), SEND_TIMEOUT));
    }
}
