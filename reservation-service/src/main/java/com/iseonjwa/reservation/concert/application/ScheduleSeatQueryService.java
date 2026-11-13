package com.iseonjwa.reservation.concert.application;

import com.iseonjwa.reservation.concert.domain.repository.SeatLayoutRow;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 회차 좌석 조회(SA 6-5, 11장). 로그인과 입장 토큰은 Gateway가 검증하므로 여기서 다시 보지 않는다.
 *
 * <p>Note: 여기에는 트랜잭션을 두지 않는다. DB 읽기는 {@link ScheduleSeatReader}만 하고 커넥션도 그동안만 쥔다.
 */
@Service
@RequiredArgsConstructor
public class ScheduleSeatQueryService {

    private final ScheduleSeatLayoutCache layoutCache;
    private final ScheduleSeatReader scheduleSeatReader;

    // 회차 좌석 배치(구역 → 열 → 번호 순)와 좌석마다 선점 가능 여부. 배치는 캐시에서, 선점 가능 여부는 요청마다 DB에서 읽는다
    public ScheduleSeatsResult getSeats(Long scheduleId) {
        HoldableSeatIds holdable = HoldableSeatIds.of(scheduleSeatReader.readHoldableSeatIds(scheduleId));
        List<SeatLayoutRow> seats = layoutCache.get(scheduleId);
        boolean[] available = new boolean[seats.size()];
        for (int i = 0; i < available.length; i++) {
            available[i] = holdable.contains(seats.get(i).seatId());
        }
        return new ScheduleSeatsResult(seats, available);
    }
}
