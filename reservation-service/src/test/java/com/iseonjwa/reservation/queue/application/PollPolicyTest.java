package com.iseonjwa.reservation.queue.application;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class PollPolicyTest {

    // 순서: admitPerSecond, entryTokenTtl, pollMin, pollMax, pollJitterRatio
    private final PollPolicy pollPolicy = new PollPolicy(new QueueProperties(
            50, Duration.ofMinutes(10), Duration.ofSeconds(3), Duration.ofSeconds(10), 0.2));

    @Test
    void 예상_대기_시간은_초당_입장_수로_나눠_올림한다() {
        assertThat(pollPolicy.estimatedWaitSeconds(50)).isEqualTo(1);
        assertThat(pollPolicy.estimatedWaitSeconds(51)).isEqualTo(2);
    }

    @Test
    void 차례가_가까우면_최소_3초에_jitter를_더한다() {
        assertThat(pollPolicy.nextPollAfterMillis(1)).isBetween(3000L, 3600L);
    }

    @Test
    void 중간이면_예상_대기_시간에_jitter를_더한다() {
        assertThat(pollPolicy.nextPollAfterMillis(6)).isBetween(6000L, 7200L);
    }

    @Test
    void 차례가_멀면_최대_10초에_jitter를_더한다() {
        assertThat(pollPolicy.nextPollAfterMillis(305)).isBetween(10000L, 12000L);
    }
}