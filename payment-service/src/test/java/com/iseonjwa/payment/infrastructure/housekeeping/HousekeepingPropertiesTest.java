package com.iseonjwa.payment.infrastructure.housekeeping;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HousekeepingPropertiesTest {
    private static HousekeepingProperties withRetention(Duration processed, Duration topic, Duration dlq) {
        return new HousekeepingProperties(true, Duration.ofMinutes(10), 1000, 100, Duration.ofMillis(100),
                Duration.ofDays(7), processed, topic, dlq);
    }

    @Test
    void 처리_이력_보관이_토픽과_DLQ_보관보다_길면_통과한다() {
        assertDoesNotThrow(() -> withRetention(Duration.ofDays(14), Duration.ofDays(7), Duration.ofDays(7)));
        assertDoesNotThrow(() -> withRetention(Duration.ofDays(14), Duration.ofDays(13), Duration.ofDays(3)));
    }

    @Test
    void 처리_이력_보관이_토픽_보관보다_짧으면_세_값을_담아_실패한다() {
        var exception = assertThrows(IllegalArgumentException.class,
                () -> withRetention(Duration.ofDays(5), Duration.ofDays(7), Duration.ofDays(3)));

        assertTrue(exception.getMessage().contains("PT120H"), exception.getMessage());
        assertTrue(exception.getMessage().contains("PT168H"), exception.getMessage());
        assertTrue(exception.getMessage().contains("PT72H"), exception.getMessage());
    }

    @Test
    void 처리_이력_보관이_DLQ_보관보다_짧으면_실패한다() {
        assertThrows(IllegalArgumentException.class,
                () -> withRetention(Duration.ofDays(8), Duration.ofDays(7), Duration.ofDays(10)));
    }

    @Test
    void 처리_이력_보관이_더_긴_쪽과_같아도_실패한다() {
        assertThrows(IllegalArgumentException.class,
                () -> withRetention(Duration.ofDays(7), Duration.ofDays(7), Duration.ofDays(3)));
        assertThrows(IllegalArgumentException.class,
                () -> withRetention(Duration.ofDays(7), Duration.ofDays(2), Duration.ofDays(7)));
    }

    @Test
    void 잘못된_숫자_설정은_실패한다() {
        var ok = Duration.ofDays(14);
        assertThrows(IllegalArgumentException.class, () -> new HousekeepingProperties(true, Duration.ZERO, 1000, 100,
                Duration.ofMillis(100), Duration.ofDays(7), ok, Duration.ofDays(7), Duration.ofDays(7)));
        assertThrows(IllegalArgumentException.class, () -> new HousekeepingProperties(true, Duration.ofMinutes(10), 0, 100,
                Duration.ofMillis(100), Duration.ofDays(7), ok, Duration.ofDays(7), Duration.ofDays(7)));
        assertThrows(IllegalArgumentException.class, () -> new HousekeepingProperties(true, Duration.ofMinutes(10), 1000, 0,
                Duration.ofMillis(100), Duration.ofDays(7), ok, Duration.ofDays(7), Duration.ofDays(7)));
        assertThrows(IllegalArgumentException.class, () -> new HousekeepingProperties(true, Duration.ofMinutes(10), 1000, 100,
                Duration.ofMillis(-1), Duration.ofDays(7), ok, Duration.ofDays(7), Duration.ofDays(7)));
        assertEquals(Duration.ZERO, new HousekeepingProperties(true, Duration.ofMinutes(10), 1000, 100,
                Duration.ZERO, Duration.ofDays(7), ok, Duration.ofDays(7), Duration.ofDays(7)).pauseBetweenBatches());
    }
}
