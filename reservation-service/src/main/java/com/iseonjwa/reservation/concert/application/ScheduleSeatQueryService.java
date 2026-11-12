package com.iseonjwa.reservation.concert.application;

import com.iseonjwa.common.response.BusinessException;
import com.iseonjwa.common.response.CommonErrorCode;
import com.iseonjwa.reservation.concert.domain.entity.ScheduleSeat;
import com.iseonjwa.reservation.concert.domain.repository.ScheduleRepository;
import com.iseonjwa.reservation.concert.domain.repository.ScheduleSeatRepository;
import com.iseonjwa.reservation.concert.domain.repository.ScheduleSeatRepository.SeatLayoutRow;
import com.iseonjwa.reservation.concert.domain.repository.ScheduleSeatRepository.SeatMapRow;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 회차 좌석 조회(SA 6-5, 11장). 로그인과 입장 토큰은 Gateway가 검증하므로 여기서 다시 보지 않는다.
 */
@Service
@RequiredArgsConstructor
public class ScheduleSeatQueryService {

    private final ScheduleRepository scheduleRepository;
    private final ScheduleSeatRepository scheduleSeatRepository;
    private final SeatMapReader seatMapReader;

    // P4b: 배치는 판매 중에 바뀌지 않아 만료가 없다. 저장소(로컬·Redis)는 SeatLayoutCache가 고른다
    private final SeatLayoutCache layoutCache;

    // P4c: 선점 가능 좌석 ID도 1초 동안 같은 값을 쓴다. 회차마다 DB 조회가 1초에 한 번으로 준다.
    // Note: 1초 안에는 이미 잡힌 좌석이 고를 수 있는 것으로 보이고, 지운 회차도 200이다. 선점 요청이 최종 판정하므로 정합성은 깨지지 않는다
    private static final Duration STATE_TTL = Duration.ofSeconds(1);
    private final SharedLoadCache<Long, long[]> stateCache = new SharedLoadCache<>(STATE_TTL, ids -> true);

    // 회차 좌석 배치를 엔티티로 읽는다(P4a 전 경로). 측정 브랜치에서는 행 응답과 JSON이 같은지 보는 테스트만 쓴다
    @Transactional(readOnly = true)
    public List<ScheduleSeat> getSeats(Long scheduleId) {
        if (scheduleRepository.findById(scheduleId).isEmpty()) {
            throw new BusinessException(CommonErrorCode.NOT_FOUND);
        }
        return scheduleSeatRepository.findByScheduleIdWithVenueSeat(scheduleId);
    }

    // P4b·P4c: 배치는 만료 없는 캐시에서, 선점 가능 여부는 1초 캐시에서 읽는다. 구역 → 열 → 번호 순이다
    public List<SeatMapRow> getSeatMap(Long scheduleId) {
        List<SeatLayoutRow> layout = layoutCache.get(scheduleId);
        long[] holdableSeatIds = stateCache.get(scheduleId, () -> seatMapReader.readHoldableSeatIds(scheduleId));
        return merge(layout, holdableSeatIds);
    }

    // 선점 사전 거절(측정 브랜치 전용). 1초 상태 캐시가 TTL 안에 있고, 요청 좌석 중 하나라도 선점 가능 목록에 없으면 true다.
    // 캐시가 없거나 낡았으면 false라 DB가 판정한다. 캐시를 새로 읽지 않으므로 선점 경로에 DB 조회를 더하지 않는다
    public boolean anyNotHoldableInFreshState(Long scheduleId, List<Long> seatIds) {
        long[] holdableSeatIds = stateCache.peekFresh(scheduleId);
        if (holdableSeatIds == null) {
            return false;
        }
        for (Long seatId : seatIds) {
            if (Arrays.binarySearch(holdableSeatIds, seatId) < 0) {
                return true;
            }
        }
        return false;
    }

    // Note: 배치를 읽은 뒤 생긴 좌석은 응답에 나오지 않는다. 측정은 좌석을 만든 회차만 부르므로 문제 되지 않는다.
    // 본 구현(ISE-189)에서는 좌석 생성·삭제 때 배치 캐시를 비우는 경로가 필요하다
    private static List<SeatMapRow> merge(List<SeatLayoutRow> layout, long[] holdableSeatIds) {
        List<SeatMapRow> rows = new ArrayList<>(layout.size());
        for (SeatLayoutRow seat : layout) {
            rows.add(new SeatMapRow(seat, Arrays.binarySearch(holdableSeatIds, seat.seatId()) >= 0));
        }
        return rows;
    }
}
