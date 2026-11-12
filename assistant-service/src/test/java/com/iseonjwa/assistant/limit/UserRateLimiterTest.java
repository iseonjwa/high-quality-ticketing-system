package com.iseonjwa.assistant.limit;

import com.iseonjwa.assistant.support.MutableClock;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class UserRateLimiterTest {
    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-10T00:00:00Z"));
    private final UserRateLimiter limiter = new UserRateLimiter(clock, 5, 30);

    @Test
    void 분당_다섯_번까지_허용하고_여섯_번째는_남은_시간과_함께_거절한다() {
        for (var i = 0; i < 5; i++) {
            assertThat(limiter.tryAcquire(1L)).isEmpty();
            clock.advance(Duration.ofSeconds(1));
        }

        var wait = limiter.tryAcquire(1L);

        // 첫 요청이 0초, 지금은 5초 뒤이므로 55초를 더 기다린다
        assertThat(wait).hasValue(Duration.ofSeconds(55));
    }

    @Test
    void 일분이_지나면_다시_허용한다() {
        for (var i = 0; i < 5; i++) {
            limiter.tryAcquire(1L);
        }
        assertThat(limiter.tryAcquire(1L)).isPresent();

        clock.advance(Duration.ofSeconds(61));

        assertThat(limiter.tryAcquire(1L)).isEmpty();
    }

    @Test
    void 거절한_요청은_세지_않는다() {
        for (var i = 0; i < 5; i++) {
            limiter.tryAcquire(1L);
        }
        for (var i = 0; i < 10; i++) {
            assertThat(limiter.tryAcquire(1L)).isPresent();
        }

        clock.advance(Duration.ofSeconds(61));

        assertThat(limiter.tryAcquire(1L)).isEmpty();
    }

    @Test
    void 사용자마다_따로_센다() {
        for (var i = 0; i < 5; i++) {
            limiter.tryAcquire(1L);
        }

        assertThat(limiter.tryAcquire(1L)).isPresent();
        assertThat(limiter.tryAcquire(2L)).isEmpty();
    }

    @Test
    void 하루_서른_번을_넘으면_가장_오래된_요청이_하루를_채울_때까지_거절한다() {
        for (var i = 0; i < 30; i++) {
            assertThat(limiter.tryAcquire(1L)).as("요청 %d", i).isEmpty();
            clock.advance(Duration.ofMinutes(2));
        }

        var wait = limiter.tryAcquire(1L);

        // 첫 요청은 60분 전이므로 23시간을 더 기다린다
        assertThat(wait).hasValue(Duration.ofHours(23));
        clock.advance(Duration.ofHours(23));
        assertThat(limiter.tryAcquire(1L)).isEmpty();
    }

    @Test
    void 하루가_지난_사용자의_기록은_지운다() {
        limiter.tryAcquire(1L);
        clock.advance(Duration.ofHours(25));

        // 다른 사용자의 요청이 정리를 일으키고, 지워진 사용자는 처음처럼 허용된다
        limiter.tryAcquire(2L);
        for (var i = 0; i < 5; i++) {
            assertThat(limiter.tryAcquire(1L)).isEmpty();
        }
    }
}
