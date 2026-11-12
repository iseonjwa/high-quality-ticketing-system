package com.iseonjwa.reservation.concert.application;

import com.iseonjwa.reservation.concert.domain.repository.ScheduleSeatRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 회차 좌석 배치를 서비스 메모리에 둔다(ISE-204 P4b, ADR 0054).
 *
 * <p>Note: Redis가 아니라 메모리에 두는 이유는 ADR 0054이다. Redis에 두면 요청마다 배치 15,000건을 받아 객체로 다시 만들어
 * P4a에서 줄인 객체 생성이 돌아온다. 배치는 회차 좌석을 만든 뒤 바뀌지 않아 비울 일이 없다.
 */
@Component
@RequiredArgsConstructor
public class ScheduleSeatLayoutCache {

    private final ScheduleSeatRepository scheduleSeatRepository;
    private final Map<Long, ScheduleSeatLayout> layouts = new ConcurrentHashMap<>();

    public ScheduleSeatLayout get(Long scheduleId) {
        ScheduleSeatLayout cached = layouts.get(scheduleId);
        if (cached != null) {
            return cached;
        }
        // Note: 좌석을 아직 만들지 않은 회차의 빈 배치는 넣지 않는다. 넣으면 나중에 좌석을 만들어도 계속 빈 배치를 준다.
        // 같은 회차의 첫 요청이 동시에 와도 결과가 같아 각자 읽고 하나만 남는다. 잠그지 않는다.
        ScheduleSeatLayout loaded = new ScheduleSeatLayout(scheduleSeatRepository.findSeatLayouts(scheduleId));
        if (loaded.isEmpty()) {
            return loaded;
        }
        ScheduleSeatLayout previous = layouts.putIfAbsent(scheduleId, loaded);
        return previous != null ? previous : loaded;
    }
}
