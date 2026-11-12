package com.iseonjwa.payment.warmup;

import com.iseonjwa.payment.observability.PaymentMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.server.context.WebServerApplicationContext;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaymentWarmupTest {
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final PaymentMetrics metrics = new PaymentMetrics(registry);
    private final AtomicLong now = new AtomicLong();

    @Test
    void 단계마다_설정한_회차만큼_실행한다() {
        var db = new AtomicInteger();
        var pg = new AtomicInteger();

        warmup(5, Duration.ofSeconds(5), new WarmupStep("db", db::incrementAndGet),
                new WarmupStep("pg", pg::incrementAndGet)).start();

        assertEquals(5, db.get());
        assertEquals(5, pg.get());
    }

    @Test
    void 첫_호출_시간과_전체_시간을_지표로_남긴다() {
        warmup(3, Duration.ofSeconds(5), new WarmupStep("db", () -> now.addAndGet(600_000_000L))).start();

        assertEquals(0.6, registry.get("payment.warmup.first.call").tag("step", "db").gauge().value(), 1e-9);
        assertEquals(1.8, registry.get("payment.warmup").gauge().value(), 1e-9);
    }

    @Test
    void 단계가_예외를_던져도_start는_던지지_않고_다음_단계로_간다() {
        var next = new AtomicInteger();
        var warmup = warmup(3, Duration.ofSeconds(5),
                new WarmupStep("db", () -> {
                    throw new IllegalStateException("DB 연결 실패");
                }),
                new WarmupStep("pg", next::incrementAndGet));

        assertDoesNotThrow(warmup::start);

        assertEquals(3, next.get());
        assertTrue(warmup.isRunning());
    }

    @Test
    void 시간_상한을_넘으면_남은_회차와_단계를_그만둔다() {
        var db = new AtomicInteger();
        var pg = new AtomicInteger();
        // 한 번에 1초가 걸리는 단계. 상한 2.5초 안에서는 3회만 돈다
        var warmup = warmup(100, Duration.ofMillis(2500),
                new WarmupStep("db", () -> {
                    db.incrementAndGet();
                    now.addAndGet(1_000_000_000L);
                }),
                new WarmupStep("pg", pg::incrementAndGet));

        warmup.start();

        assertEquals(3, db.get());
        assertEquals(0, pg.get());
    }

    @Test
    void 웹_서버_시작보다_먼저_시작한다() {
        assertTrue(PaymentWarmup.PHASE < WebServerApplicationContext.START_STOP_LIFECYCLE_PHASE);
        assertEquals(PaymentWarmup.PHASE, warmup(1, Duration.ofSeconds(1)).getPhase());
    }

    @Test
    void 설정은_기본값이_있고_범위를_벗어나면_기동을_막는다() {
        var defaults = new WarmupProperties(null, null, null);
        assertTrue(defaults.enabled());
        assertEquals(20, defaults.iterations());
        assertEquals(Duration.ofSeconds(5), defaults.maxDuration());
        assertNotNull(new WarmupProperties(false, 1000, Duration.ofMillis(1)));
        assertThrows(IllegalArgumentException.class, () -> new WarmupProperties(true, 0, null));
        assertThrows(IllegalArgumentException.class, () -> new WarmupProperties(true, 1001, null));
        assertThrows(IllegalArgumentException.class, () -> new WarmupProperties(true, 20, Duration.ZERO));
    }

    private PaymentWarmup warmup(int iterations, Duration maxDuration, WarmupStep... steps) {
        return new PaymentWarmup(List.of(steps), new WarmupProperties(true, iterations, maxDuration), metrics, now::get);
    }
}
