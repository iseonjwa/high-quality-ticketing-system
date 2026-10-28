package com.iseonjwa.common.messaging.outbox;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OutboxPropertiesTest {
    @Test
    void 임대가_전송_시한의_2배_이상이면_통과한다() {
        assertDoesNotThrow(() -> new OutboxProperties(20, Duration.ofSeconds(24), Duration.ofSeconds(12)));
    }

    @Test
    void 임대가_전송_시한의_2배보다_짧으면_기동을_막는다() {
        assertThrows(IllegalArgumentException.class,
                () -> new OutboxProperties(20, Duration.ofSeconds(23), Duration.ofSeconds(12)));
    }

    @Test
    void 배치_크기가_1_미만이면_기동을_막는다() {
        assertThrows(IllegalArgumentException.class,
                () -> new OutboxProperties(0, Duration.ofSeconds(30), Duration.ofSeconds(12)));
    }

    @Test
    void 전송_시한이_0이면_기동을_막는다() {
        assertThrows(IllegalArgumentException.class,
                () -> new OutboxProperties(20, Duration.ofSeconds(30), Duration.ZERO));
    }
}
