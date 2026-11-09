package com.iseonjwa.reservation.concert.application;

import com.iseonjwa.common.response.BusinessException;
import com.iseonjwa.common.response.CommonErrorCode;
import com.iseonjwa.reservation.concert.domain.entity.ScheduleSeat;
import com.iseonjwa.reservation.concert.domain.repository.ScheduleRepository;
import com.iseonjwa.reservation.concert.domain.repository.ScheduleSeatRepository;
import com.iseonjwa.reservation.concert.domain.repository.ScheduleSeatRepository.SeatLayoutRow;
import com.iseonjwa.reservation.concert.domain.repository.ScheduleSeatRepository.SeatMapRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 회차 좌석 조회(SA 6-5, 11장). 로그인과 입장 토큰은 Gateway가 검증하므로 여기서 다시 보지 않는다.
 *
 * 측정 브랜치(experiment/ISE-79-p4-measure)에서는 경량화 단계(P4a·P4b·P4c)를 설정으로 고른다(SeatMapProperties).
 * ENTITY는 getSeats, 나머지는 getSeatMap을 쓴다.
 */
@Service
public class ScheduleSeatQueryService {

    private final ScheduleRepository scheduleRepository;
    private final ScheduleSeatRepository scheduleSeatRepository;
    private final SeatMapReader seatMapReader;
    private final SeatMapProperties properties;

    // 배치는 판매 중에 바뀌지 않아 만료가 없다. 좌석을 아직 만들지 않은 회차(빈 목록)는 남기지 않는다
    private final SharedLoadCache<Long, List<SeatLayoutRow>> layoutCache =
            new SharedLoadCache<>(null, layout -> !layout.isEmpty());
    private final SharedLoadCache<Long, long[]> stateCache;

    public ScheduleSeatQueryService(ScheduleRepository scheduleRepository,
                                    ScheduleSeatRepository scheduleSeatRepository,
                                    SeatMapReader seatMapReader,
                                    SeatMapProperties properties) {
        this.scheduleRepository = scheduleRepository;
        this.scheduleSeatRepository = scheduleSeatRepository;
        this.seatMapReader = seatMapReader;
        this.properties = properties;
        this.stateCache = new SharedLoadCache<>(properties.stateTtl(), ids -> true);
    }

    public SeatMapProperties.Mode mode() {
        return properties.mode();
    }

    // P0'': 회차 좌석 배치를 엔티티로 읽는다. 구역 → 열 → 번호 순이다
    @Transactional(readOnly = true)
    public List<ScheduleSeat> getSeats(Long scheduleId) {
        if (scheduleRepository.findById(scheduleId).isEmpty()) {
            throw new BusinessException(CommonErrorCode.NOT_FOUND);
        }
        return scheduleSeatRepository.findByScheduleIdWithVenueSeat(scheduleId);
    }

    // P4a~P4c. 구역 → 열 → 번호 순이다
    public List<SeatMapRow> getSeatMap(Long scheduleId) {
        return switch (properties.mode()) {
            case DTO -> seatMapReader.readRows(scheduleId);
            case LAYOUT_CACHE -> merge(layoutOf(scheduleId), seatMapReader.readHoldableSeatIds(scheduleId));
            case STATE_CACHE -> merge(layoutOf(scheduleId), holdableSeatIdsOf(scheduleId));
            case ENTITY -> throw new IllegalStateException("ENTITY 단계는 getSeats를 쓴다");
        };
    }

    private List<SeatLayoutRow> layoutOf(Long scheduleId) {
        return layoutCache.get(scheduleId, () -> seatMapReader.readLayout(scheduleId));
    }

    // TTL 0은 P4b와 같다(요청마다 읽는다). P4c의 TTL 0·1·3초 비교에서 기준이 된다
    private long[] holdableSeatIdsOf(Long scheduleId) {
        if (properties.stateTtl().isZero()) {
            return seatMapReader.readHoldableSeatIds(scheduleId);
        }
        return stateCache.get(scheduleId, () -> seatMapReader.readHoldableSeatIds(scheduleId));
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
