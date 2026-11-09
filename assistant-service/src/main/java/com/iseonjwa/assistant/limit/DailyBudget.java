package com.iseonjwa.assistant.limit;

import java.time.Clock;
import java.time.LocalDate;

/**
 * 하루에 Claude를 부를 수 있는 전체 횟수다. 날짜가 바뀌면(시계의 시간대 기준) 다시 센다.
 * 넘으면 오류 대신 검색 모드로 답하게 하려는 값이라, 호출하는 쪽이 {@link #tryUse()}의 결과로 가른다.
 */
public final class DailyBudget {
    private final Clock clock;
    private final int limit;
    private LocalDate day;
    private int used;

    public DailyBudget(Clock clock, int limit) {
        this.clock = clock;
        this.limit = limit;
        this.day = LocalDate.now(clock);
    }

    public synchronized boolean tryUse() {
        var today = LocalDate.now(clock);
        if (!today.equals(day)) {
            day = today;
            used = 0;
        }
        if (used >= limit) {
            return false;
        }
        used++;
        return true;
    }
}
