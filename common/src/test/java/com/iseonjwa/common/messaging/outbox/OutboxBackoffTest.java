package com.iseonjwa.common.messaging.outbox;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OutboxBackoffTest {
    @Test
    void 기본_지연은_500ms에서_시작해_두_배씩_늘고_60초에서_멈춘다() {
        assertEquals(Duration.ofMillis(500), OutboxBackoff.baseDelay(1));
        assertEquals(Duration.ofSeconds(1), OutboxBackoff.baseDelay(2));
        assertEquals(Duration.ofSeconds(2), OutboxBackoff.baseDelay(3));
        assertEquals(Duration.ofSeconds(32), OutboxBackoff.baseDelay(7));
        assertEquals(Duration.ofSeconds(60), OutboxBackoff.baseDelay(8));
    }

    @Test
    void 실패_횟수가_아주_커도_넘치지_않고_60초다() {
        assertEquals(Duration.ofSeconds(60), OutboxBackoff.baseDelay(64));
        assertEquals(Duration.ofSeconds(60), OutboxBackoff.baseDelay(32767));
        assertEquals(Duration.ofSeconds(60), OutboxBackoff.baseDelay(Integer.MAX_VALUE));
    }

    @Test
    void 난수가_0이면_기본_지연의_절반이다() {
        var backoff = new OutboxBackoff(() -> 0.0);

        assertEquals(Duration.ofMillis(250), backoff.delay(1));
        assertEquals(Duration.ofSeconds(30), backoff.delay(8));
    }

    @Test
    void 난수가_1에_가까우면_기본_지연과_같고_넘지_않는다() {
        var backoff = new OutboxBackoff(() -> Math.nextDown(1.0));

        assertEquals(Duration.ofMillis(500), backoff.delay(1));
        assertEquals(Duration.ofSeconds(60), backoff.delay(8));
        assertEquals(Duration.ofSeconds(60), backoff.delay(Integer.MAX_VALUE));
    }

    @Test
    void 난수원이_무엇이든_지연은_기본_지연의_50에서_100퍼센트다() {
        for (var failures : new int[] {1, 2, 5, 8, 100, 32767}) {
            var base = OutboxBackoff.baseDelay(failures);
            for (var fraction : new double[] {0.0, 0.25, 0.5, 0.75, Math.nextDown(1.0)}) {
                var delay = new OutboxBackoff(() -> fraction).delay(failures);
                assertTrue(delay.compareTo(base.dividedBy(2)) >= 0, failures + "회, 난수 " + fraction + ": " + delay);
                assertTrue(delay.compareTo(base) <= 0, failures + "회, 난수 " + fraction + ": " + delay);
                assertTrue(delay.compareTo(Duration.ofSeconds(60)) <= 0);
            }
        }
    }

    @Test
    void 기본_난수원도_범위_안이다() {
        var backoff = OutboxBackoff.withRandomJitter();

        for (var i = 0; i < 1000; i++) {
            var delay = backoff.delay(3);
            assertTrue(delay.compareTo(Duration.ofSeconds(1)) >= 0 && delay.compareTo(Duration.ofSeconds(2)) <= 0, delay.toString());
        }
    }
}
