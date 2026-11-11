package com.iseonjwa.reservation.seathold.application;

import com.iseonjwa.reservation.concert.application.ScheduleSeatQueryService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 선점 사전 거절(측정 브랜치 전용, STEPS.md "선점 사전 거절 비교").
 * 좌석 조회의 1초 상태 캐시(P4c)에 이미 선점 불가로 보이는 좌석이 섞이면 트랜잭션을 열지 않고 409로 돌려보낸다.
 *
 * Note: 판정이 아니라 "확실히 질 요청"만 거른다. 캐시가 비었다고 보는 좌석은 DB 조건부 UPDATE가 막으므로 중복 판매는 생기지 않는다.
 * 대가는 1초 안에 풀린 좌석(만료·취소)에 대한 잘못된 409다. 회차에 속하지 않는 좌석 ID도 400 대신 409가 된다
 */
@Component
public class HeldSeatPreCheck {
    private final ScheduleSeatQueryService seatQuery;
    private final boolean enabled;
    private final Counter rejected;

    public HeldSeatPreCheck(ScheduleSeatQueryService seatQuery, MeterRegistry registry,
            @Value("${seathold.precheck.enabled:false}") boolean enabled) {
        this.seatQuery = seatQuery;
        this.enabled = enabled;
        this.rejected = Counter.builder("seathold.precheck.rejected").register(registry);
    }

    boolean surelyHeld(Long scheduleId, List<Long> sortedSeatIds) {
        if (!enabled || !seatQuery.anyNotHoldableInFreshState(scheduleId, sortedSeatIds)) {
            return false;
        }
        rejected.increment();
        return true;
    }
}
