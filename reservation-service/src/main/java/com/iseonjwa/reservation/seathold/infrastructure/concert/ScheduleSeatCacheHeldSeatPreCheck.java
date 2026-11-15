package com.iseonjwa.reservation.seathold.infrastructure.concert;

import com.iseonjwa.reservation.concert.application.ScheduleSeatQueryService;
import com.iseonjwa.reservation.seathold.application.HeldSeatPreCheck;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 좌석 조회의 1초 선점 가능 캐시(P4c, ISE-205)로 사전 거절을 판단한다. 의존 방향은 seathold → concert다(ADR 0034).
 *
 * <p>Note: 캐시는 누군가 그 회차 좌석 조회를 불러야 채워진다. 좌석 조회 없이 선점만 오면 늘 false라 걸러지는 요청이 없다.
 * {@code seat-hold.pre-reject.enabled=false}로 끄면 이전 경로와 같아져, 같은 버전에서 켠 회와 끈 회를 비교할 수 있다.
 */
@Component
public class ScheduleSeatCacheHeldSeatPreCheck implements HeldSeatPreCheck {

    private final ScheduleSeatQueryService scheduleSeats;
    private final boolean enabled;

    public ScheduleSeatCacheHeldSeatPreCheck(ScheduleSeatQueryService scheduleSeats,
            @Value("${seat-hold.pre-reject.enabled:true}") boolean enabled) {
        this.scheduleSeats = scheduleSeats;
        this.enabled = enabled;
    }

    @Override
    public boolean anyNotHoldable(Long scheduleId, List<Long> seatIds) {
        return enabled && scheduleSeats.anyNotHoldable(scheduleId, seatIds);
    }
}
