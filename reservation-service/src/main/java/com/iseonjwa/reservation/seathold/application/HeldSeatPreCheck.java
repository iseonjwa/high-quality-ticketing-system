package com.iseonjwa.reservation.seathold.application;

import com.iseonjwa.reservation.concert.application.ScheduleSeatQueryService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 선점 사전 거절(측정 브랜치 전용, STEPS.md "선점 사전 거절 비교").
 * 좌석 조회의 1초 상태 캐시(P4c)에 이미 선점 불가로 보이는 좌석이 섞이면 트랜잭션을 열지 않고 409로 돌려보낸다.
 *
 * Note: 판정이 아니라 "확실히 질 요청"만 거른다. 캐시가 비었다고 보는 좌석은 DB 조건부 UPDATE가 막으므로 중복 판매는 생기지 않는다.
 * 대가는 낡은 캐시 때문에 실제로는 선점 가능한 좌석을 거르는 잘못된 409다. 회차에 속하지 않는 좌석 ID도 400 대신 409가 된다
 */
@Component
public class HeldSeatPreCheck {
    // 스냅숏과 같은 기준(SpringDataScheduleSeatRepository.findHoldableSeatIds)으로 지금 선점 가능한 좌석 수를 센다
    private static final String HOLDABLE_NOW = """
            SELECT count(*) FROM schedule_seats
            WHERE schedule_id = ? AND id = ANY (?) AND deleted_at IS NULL
              AND status = 'AVAILABLE' AND grade <> 'LOTTERY'
            """;

    private final ScheduleSeatQueryService seatQuery;
    private final JdbcTemplate jdbc;
    private final boolean enabled;
    private final boolean lightKeyLookup;
    private final double verifyRate;
    private final Counter rejected;
    private final Counter verified;
    private final Counter falseRejected;

    public HeldSeatPreCheck(ScheduleSeatQueryService seatQuery, JdbcTemplate jdbc, MeterRegistry registry,
            @Value("${seathold.precheck.enabled:false}") boolean enabled,
            @Value("${seathold.precheck.light-key-lookup:false}") boolean lightKeyLookup,
            @Value("${seathold.precheck.verify-rate:0}") double verifyRate) {
        this.seatQuery = seatQuery;
        this.jdbc = jdbc;
        this.enabled = enabled;
        this.lightKeyLookup = lightKeyLookup;
        this.verifyRate = verifyRate;
        this.rejected = Counter.builder("seathold.precheck.rejected").register(registry);
        this.verified = Counter.builder("seathold.precheck.verified").register(registry);
        this.falseRejected = Counter.builder("seathold.precheck.false.rejected").register(registry);
    }

    boolean surelyHeld(Long scheduleId, List<Long> sortedSeatIds) {
        if (!enabled || !seatQuery.anyNotHoldableInFreshState(scheduleId, sortedSeatIds)) {
            return false;
        }
        rejected.increment();
        if (verifyRate > 0 && ThreadLocalRandom.current().nextDouble() < verifyRate) {
            verify(scheduleId, sortedSeatIds);
        }
        return true;
    }

    boolean lightKeyLookup() {
        return lightKeyLookup;
    }

    // 표본 검증(측정 전용). 거른 순간 DB에서 요청 좌석이 모두 선점 가능이면 DB로 보냈어야 할 요청, 곧 잘못된 거절이다
    private void verify(Long scheduleId, List<Long> seatIds) {
        verified.increment();
        Integer holdable = jdbc.queryForObject(HOLDABLE_NOW, Integer.class, scheduleId, seatIds.toArray(Long[]::new));
        if (holdable != null && holdable == seatIds.size()) {
            falseRejected.increment();
        }
    }
}
