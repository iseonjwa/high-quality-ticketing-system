package com.iseonjwa.reservation.seathold.presentation;

import com.iseonjwa.common.response.BusinessException;
import com.iseonjwa.common.response.CommonErrorCode;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SeatHoldConcurrencyLimitTest {

    @Test
    void 상한만큼_처리_중이면_다음_요청은_기다리지_않고_503으로_거절하고_자리가_나면_다시_받는다() throws Exception {
        var registry = new SimpleMeterRegistry();
        var limit = new SeatHoldConcurrencyLimit(1, Duration.ZERO, 64, registry);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var holder = Thread.ofPlatform().start(() -> limit.run(() -> {
            entered.countDown();
            await(release);
            return "first";
        }));
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

        assertThatThrownBy(() -> limit.run(() -> "second"))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(CommonErrorCode.SERVICE_UNAVAILABLE));
        assertThat(registry.counter("seathold.concurrency.rejected", "reason", "wait_timeout").count()).isEqualTo(1.0);

        release.countDown();
        holder.join();
        assertThat(limit.run(() -> "third")).isEqualTo("third");
    }

    @Test
    void 대기_인원이_차_있으면_자리_대기_시간과_상관없이_바로_503으로_거절한다() throws Exception {
        var registry = new SimpleMeterRegistry();
        // 자리 1개, 대기 5초, 대기 인원 0명. 자리가 차 있으면 기다리지 않아야 한다
        var limit = new SeatHoldConcurrencyLimit(1, Duration.ofSeconds(5), 0, registry);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var holder = Thread.ofPlatform().start(() -> limit.run(() -> {
            entered.countDown();
            await(release);
            return "first";
        }));
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

        var startedAt = System.nanoTime();
        assertThatThrownBy(() -> limit.run(() -> "second")).isInstanceOf(BusinessException.class);
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)).isLessThan(1_000);
        assertThat(registry.counter("seathold.concurrency.rejected", "reason", "queue_full").count()).isEqualTo(1.0);
        assertThat(registry.counter("seathold.concurrency.rejected", "reason", "wait_timeout").count()).isZero();

        release.countDown();
        holder.join();
        assertThat(limit.run(() -> "third")).isEqualTo("third");
    }

    @Test
    void 처리_중_예외가_나도_자리를_돌려준다() {
        var limit = new SeatHoldConcurrencyLimit(1, Duration.ZERO, 64, new SimpleMeterRegistry());

        assertThatThrownBy(() -> limit.run(() -> {
            throw new IllegalStateException("실패");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(limit.run(() -> "next")).isEqualTo("next");
    }

    @Test
    void 상한이_0이면_끈다() {
        var limit = new SeatHoldConcurrencyLimit(0, Duration.ZERO, 64, new SimpleMeterRegistry());

        assertThat(limit.run(() -> "ok")).isEqualTo("ok");
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}
