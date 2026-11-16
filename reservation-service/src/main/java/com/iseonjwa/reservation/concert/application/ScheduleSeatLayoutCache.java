package com.iseonjwa.reservation.concert.application;

import com.iseonjwa.reservation.concert.domain.repository.SeatLayoutRow;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 회차 좌석 배치를 서비스 메모리에 둔다(ISE-204 P4b, ADR 0054).
 *
 * <p>Note: Redis가 아니라 메모리에 두는 이유는 ADR 0054이다. Redis에 두면 요청마다 배치 15,000건을 받아 객체로 다시 만들어
 * P4a에서 줄인 객체 생성이 돌아온다. 배치는 회차 좌석을 만든 뒤 바뀌지 않아 만료가 없다.
 */
@Component
@RequiredArgsConstructor
public class ScheduleSeatLayoutCache {

    private final ScheduleSeatReader scheduleSeatReader;

    // 오픈 직후처럼 캐시가 빈 순간 같은 회차 요청이 몰려도 DB에서는 한 번만 읽는다.
    // 좌석을 아직 만들지 않은 회차의 빈 배치는 남기지 않는다. 남기면 나중에 좌석을 만들어도 계속 빈 배치를 준다
    private final SharedLoadCache<Long, List<SeatLayoutRow>> layouts =
            new SharedLoadCache<>(null, layout -> !layout.isEmpty());

    // 구역 → 열 → 번호 순이고 바꿀 수 없는 목록이다. 여러 요청이 같이 읽는다
    public List<SeatLayoutRow> get(Long scheduleId) {
        return layouts.get(scheduleId, () -> List.copyOf(scheduleSeatReader.readLayout(scheduleId)));
    }
}
