package com.iseonjwa.payment.billing;

import com.iseonjwa.payment.billing.application.BillingKeyRegistrationLimiter;
import com.iseonjwa.payment.billing.application.BillingKeyRegistrationProperties;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BillingKeyRegistrationLimiterTest {
    private final TestClock clock = new TestClock(Instant.parse("2026-11-12T00:00:00Z"));
    private final BillingKeyRegistrationLimiter limiter = new BillingKeyRegistrationLimiter(clock, 3, 10);

    @Test
    void 분당_세_번까지_허용하고_네_번째는_남은_시간과_함께_거절한다() {
        for (var i = 0; i < 3; i++) {
            assertTrue(limiter.tryAcquire(1L).isEmpty());
            clock.advance(Duration.ofSeconds(1));
        }

        // 첫 시도가 0초, 지금은 3초 뒤이므로 57초를 더 기다린다
        assertEquals(Optional.of(Duration.ofSeconds(57)), limiter.tryAcquire(1L));
    }

    @Test
    void 일분이_지나면_다시_허용한다() {
        for (var i = 0; i < 3; i++) limiter.tryAcquire(1L);

        clock.advance(Duration.ofSeconds(61));

        assertTrue(limiter.tryAcquire(1L).isEmpty());
    }

    @Test
    void 거절한_요청은_세지_않는다() {
        for (var i = 0; i < 3; i++) limiter.tryAcquire(1L);
        for (var i = 0; i < 20; i++) assertTrue(limiter.tryAcquire(1L).isPresent());

        clock.advance(Duration.ofSeconds(61));

        // 거절을 셌다면 창이 지나도 상한에 걸려 있다
        assertTrue(limiter.tryAcquire(1L).isEmpty());
    }

    @Test
    void 하루_열_번을_넘기면_분이_지나도_거절하고_다시_시도_시간은_하루_창_기준이다() {
        for (var i = 0; i < 10; i++) {
            assertTrue(limiter.tryAcquire(1L).isEmpty());
            clock.advance(Duration.ofMinutes(2));
        }

        var wait = limiter.tryAcquire(1L);

        // 첫 시도가 20분 전이므로 23시간 40분 뒤에 풀린다
        assertEquals(Optional.of(Duration.ofHours(23).plusMinutes(40)), wait);
    }

    @Test
    void 하루가_지나면_다시_허용한다() {
        for (var i = 0; i < 10; i++) {
            limiter.tryAcquire(1L);
            clock.advance(Duration.ofMinutes(2));
        }

        clock.advance(Duration.ofDays(1));

        assertTrue(limiter.tryAcquire(1L).isEmpty());
    }

    @Test
    void 사용자끼리_독립이다() {
        for (var i = 0; i < 3; i++) limiter.tryAcquire(1L);

        assertTrue(limiter.tryAcquire(1L).isPresent());
        assertTrue(limiter.tryAcquire(2L).isEmpty());
    }

    @Test
    void 같은_사용자가_동시에_시도해도_허용은_분_상한만큼이다() throws Exception {
        var threads = 20;
        var pool = Executors.newFixedThreadPool(threads);
        var ready = new CountDownLatch(threads);
        var start = new CountDownLatch(1);
        var allowed = new AtomicInteger();
        var futures = new ArrayList<java.util.concurrent.Future<?>>();
        for (var i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                start.await();
                if (limiter.tryAcquire(7L).isEmpty()) allowed.incrementAndGet();
                return null;
            }));
        }
        ready.await();
        start.countDown();
        for (var future : futures) future.get(10, TimeUnit.SECONDS);
        pool.shutdown();

        assertEquals(3, allowed.get());
    }

    @Test
    void 설정은_1_미만이면_기동을_막고_없으면_기본값이다() {
        assertThrows(IllegalArgumentException.class, () -> new BillingKeyRegistrationProperties.RegistrationLimit(0, 10));
        assertThrows(IllegalArgumentException.class, () -> new BillingKeyRegistrationProperties.RegistrationLimit(3, 0));
        var defaults = new BillingKeyRegistrationProperties(null).registrationLimit();
        assertEquals(3, defaults.perMinute());
        assertEquals(10, defaults.perDay());
    }

    private static final class TestClock extends Clock {
        private volatile Instant now;

        TestClock(Instant start) {
            this.now = start;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
