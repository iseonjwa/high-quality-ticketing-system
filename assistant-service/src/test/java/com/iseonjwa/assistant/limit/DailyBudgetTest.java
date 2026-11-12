package com.iseonjwa.assistant.limit;

import com.iseonjwa.assistant.support.MutableClock;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

class DailyBudgetTest {
    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-10T01:00:00Z"), ZoneId.of("Asia/Seoul"));

    @Test
    void 상한까지만_허용하고_날짜가_바뀌면_다시_센다() {
        var budget = new DailyBudget(clock, 3);

        assertThat(budget.tryUse()).isTrue();
        assertThat(budget.tryUse()).isTrue();
        assertThat(budget.tryUse()).isTrue();
        assertThat(budget.tryUse()).isFalse();

        // 서울 기준 다음 날 00시 이후
        clock.advance(Duration.ofHours(15));

        assertThat(budget.tryUse()).isTrue();
    }
}
