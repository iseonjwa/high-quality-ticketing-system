package com.iseonjwa.assistant.limit;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 사용자당 분·일 요청 상한이다. 메모리에만 두므로 재시작하면 초기화되고, 인스턴스가 여러 개면 인스턴스마다 센다.
 * 분은 최근 60초, 일은 최근 24시간의 허용된 요청 수로 센다. 거절한 요청은 세지 않는다.
 */
public final class UserRateLimiter {
    private static final Duration MINUTE = Duration.ofMinutes(1);
    private static final Duration DAY = Duration.ofDays(1);

    private final Clock clock;
    private final int perMinute;
    private final int perDay;
    private final ConcurrentHashMap<Long, ArrayDeque<Instant>> accepted = new ConcurrentHashMap<>();
    private Instant lastSweepAt;

    public UserRateLimiter(Clock clock, int perMinute, int perDay) {
        this.clock = clock;
        this.perMinute = perMinute;
        this.perDay = perDay;
        this.lastSweepAt = clock.instant();
    }

    /** 허용하면 기록하고 빈 값을, 상한을 넘으면 다시 시도할 수 있을 때까지의 시간을 돌려준다. */
    public Optional<Duration> tryAcquire(long userId) {
        var now = clock.instant();
        sweepIdleUsers(now);
        var history = accepted.computeIfAbsent(userId, id -> new ArrayDeque<>());
        synchronized (history) {
            dropOlderThanDay(history, now);
            var wait = longestWait(history, now);
            if (wait.isPresent()) {
                return wait;
            }
            history.addLast(now);
            return Optional.empty();
        }
    }

    private Optional<Duration> longestWait(ArrayDeque<Instant> history, Instant now) {
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
        return wait.isZero() ? Optional.empty() : Optional.of(wait);
    }

    private Duration until(Instant firstAcceptedAt, Duration window, Instant now) {
        var wait = Duration.between(now, firstAcceptedAt.plus(window));
        return wait.isNegative() || wait.isZero() ? Duration.ofMillis(1) : wait;
    }

    private void dropOlderThanDay(ArrayDeque<Instant> history, Instant now) {
        while (!history.isEmpty() && !history.peekFirst().isAfter(now.minus(DAY))) {
            history.pollFirst();
        }
    }

    // Note: 사용자 수만큼 맵이 자라지 않도록 한 시간에 한 번 하루 안에 요청이 없는 사용자를 지운다
    private synchronized void sweepIdleUsers(Instant now) {
        if (Duration.between(lastSweepAt, now).compareTo(Duration.ofHours(1)) < 0) {
            return;
        }
        lastSweepAt = now;
        accepted.entrySet().removeIf(entry -> {
            synchronized (entry.getValue()) {
                dropOlderThanDay(entry.getValue(), now);
                return entry.getValue().isEmpty();
            }
        });
    }
}
