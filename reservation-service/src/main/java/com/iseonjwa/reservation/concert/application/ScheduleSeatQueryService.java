package com.iseonjwa.reservation.concert.application;

import com.iseonjwa.reservation.concert.domain.repository.SeatLayoutRow;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;

/**
 * 회차 좌석 조회(SA 6-5, 11장). 로그인과 입장 토큰은 Gateway가 검증하므로 여기서 다시 보지 않는다.
 *
 * <p>Note: 여기에는 트랜잭션을 두지 않는다. DB 읽기는 {@link ScheduleSeatReader}만 하고 커넥션도 그동안만 쥔다.
 */
@Service
@RequiredArgsConstructor
public class ScheduleSeatQueryService {

    // P4c(SA 9-4 좌석 상태 1초): 선점 가능 여부를 회차마다 1초 동안 같은 값으로 쓴다. DB 조회가 회차마다 1초에 한 번으로 준다.
    // Note: 1초 안에는 이미 잡힌 좌석이 고를 수 있는 것으로 보이고, 지운 회차도 200이다.
    // 최종 판정은 선점의 조건부 UPDATE가 해서 이중 판매는 생기지 않는다(ISE-205)
    private static final Duration HOLDABLE_TTL = Duration.ofSeconds(1);

    private final ScheduleSeatLayoutCache layoutCache;
    private final ScheduleSeatReader scheduleSeatReader;

    // 만료된 회차는 한 요청만 다시 읽고 나머지는 그 결과를 기다린다(캐시 스탬피드 방지)
    private final SharedLoadCache<Long, HoldableSeatIds> holdableCache =
            new SharedLoadCache<>(HOLDABLE_TTL, ids -> true);

    // 회차 좌석 배치(구역 → 열 → 번호 순)와 좌석마다 선점 가능 여부. 배치는 만료 없는 캐시에서, 선점 가능 여부는 1초 캐시에서 읽는다
    public ScheduleSeatsResult getSeats(Long scheduleId) {
        List<SeatLayoutRow> seats = layoutCache.get(scheduleId);
        // 좌석을 아직 만들지 않은 회차는 선점 가능 여부를 읽지도 캐시하지도 않는다.
        // 캐시하면 좌석을 만든 직후 1초 동안 모든 좌석이 고를 수 없는 것으로 보인다
        if (seats.isEmpty()) {
            return new ScheduleSeatsResult(seats, new boolean[0]);
        }
        HoldableSeatIds holdable = holdableCache.get(scheduleId,
                () -> HoldableSeatIds.of(scheduleSeatReader.readHoldableSeatIds(scheduleId)));
        boolean[] available = new boolean[seats.size()];
        for (int i = 0; i < available.length; i++) {
            available[i] = holdable.contains(seats.get(i).seatId());
        }
        return new ScheduleSeatsResult(seats, available);
    }
}
