package com.iseonjwa.payment.billing.application;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 사용자당 빌링키 등록(PG 발급 시도)의 분·일 상한이다. 최근 60초, 최근 24시간의 허용된 시도 수로 센다(슬라이딩 윈도 로그).
 * 허용한 시도는 PG 결과와 상관없이 세고, 거절한 요청은 세지 않는다. 메모리에만 두므로 재시작하면 초기화되고 인스턴스마다 센다.
 */
@Component
public class BillingKeyRegistrationLimiter {
    private static final Duration MINUTE = Duration.ofMinutes(1);
    private static final Duration DAY = Duration.ofDays(1);
    private static final Duration SWEEP_INTERVAL = Duration.ofHours(1);

    private final Clock clock;
    private final int perMinute;
    private final int perDay;
    private final ConcurrentHashMap<Long, ArrayDeque<Instant>> accepted = new ConcurrentHashMap<>();
    private Instant lastSweepAt;

    @Autowired
    public BillingKeyRegistrationLimiter(@Qualifier("commonAuditingClock") Clock clock,
            BillingKeyRegistrationProperties properties) {
        this(clock, properties.registrationLimit().perMinute(), properties.registrationLimit().perDay());
    }

    public BillingKeyRegistrationLimiter(Clock clock, int perMinute, int perDay) {
        this.clock = clock;
        this.perMinute = perMinute;
        this.perDay = perDay;
        this.lastSweepAt = clock.instant();
    }

    /** 허용하면 기록하고 빈 값을, 상한을 넘으면 다시 시도할 수 있을 때까지의 시간을 돌려준다. */
    public Optional<Duration> tryAcquire(long userId) {
        var now = clock.instant();
        sweepIdleUsers(now);
        var wait = new Duration[] {Duration.ZERO};
        // Note: 검사와 기록을 compute 한 구역에서 한다. 따로 하면 동시 요청이 모두 검사를 통과한다(check-then-act 경쟁)
        accepted.compute(userId, (id, history) -> {
            history = history != null ? history : new ArrayDeque<>();
            dropOlderThanDay(history, now);
            wait[0] = longestWait(history, now);
            if (wait[0].isZero()) {
                history.addLast(now);
            }
            return history;
        });
        return wait[0].isZero() ? Optional.empty() : Optional.of(wait[0]);
    }

    private Duration longestWait(ArrayDeque<Instant> history, Instant now) {
        var wait = Duration.ZERO;
        if (history.size() >= perDay) {
            wait = until(history.peekFirst(), DAY, now);
        }
        var inLastMinute = history.stream().filter(at -> at.isAfter(now.minus(MINUTE))).toList();
        if (inLastMinute.size() >= perMinute) {
            var oldestOfWindow = inLastMinute.get(inLastMinute.size() - perMinute);
            var minuteWait = until(oldestOfWindow, MINUTE, now);
            wait = minuteWait.compareTo(wait) > 0 ? minuteWait : wait;
        }
        return wait;
    }

    private static Duration until(Instant acceptedAt, Duration window, Instant now) {
        var wait = Duration.between(now, acceptedAt.plus(window));
        return wait.isNegative() || wait.isZero() ? Duration.ofMillis(1) : wait;
    }

    private static void dropOlderThanDay(ArrayDeque<Instant> history, Instant now) {
        while (!history.isEmpty() && !history.peekFirst().isAfter(now.minus(DAY))) {
            history.pollFirst();
        }
    }

    // Note: 사용자 수만큼 맵이 자라지 않도록 한 시간에 한 번 하루 안에 시도가 없는 사용자를 지운다. 사용자별 compute 안에서 지워 기록과 겹치지 않는다
    private synchronized void sweepIdleUsers(Instant now) {
        if (Duration.between(lastSweepAt, now).compareTo(SWEEP_INTERVAL) < 0) {
            return;
        }
        lastSweepAt = now;
        for (var userId : accepted.keySet()) {
            accepted.computeIfPresent(userId, (id, history) -> {
                dropOlderThanDay(history, now);
                return history.isEmpty() ? null : history;
            });
        }
    }
}
