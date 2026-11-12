package com.iseonjwa.reservation.concert.application;

import com.iseonjwa.common.response.BusinessException;
import com.iseonjwa.common.response.CommonErrorCode;
import com.iseonjwa.reservation.concert.domain.repository.ScheduleRepository;
import com.iseonjwa.reservation.concert.domain.repository.ScheduleSeatRepository;
import com.iseonjwa.reservation.concert.domain.repository.SeatStateRow;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 회차 좌석 조회(SA 6-5, 11장). 로그인과 입장 토큰은 Gateway가 검증하므로 여기서 다시 보지 않는다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ScheduleSeatQueryService {

    private final ScheduleRepository scheduleRepository;
    private final ScheduleSeatRepository scheduleSeatRepository;
    private final ScheduleSeatLayoutCache layoutCache;

    // 회차 좌석 배치(구역 → 열 → 번호 순)와 좌석마다 선점 가능 여부. 배치는 캐시에서, 등급·상태는 요청마다 DB에서 읽는다
    public ScheduleSeatsResult getSeats(Long scheduleId) {
        if (scheduleRepository.findById(scheduleId).isEmpty()) {
            throw new BusinessException(CommonErrorCode.NOT_FOUND);
        }
        ScheduleSeatLayout layout = layoutCache.get(scheduleId);
        boolean[] available = new boolean[layout.size()];
        for (SeatStateRow state : scheduleSeatRepository.findSeatStates(scheduleId)) {
            int position = layout.positionOf(state.seatId());
            if (position >= 0) {
                available[position] = state.isHoldable();
            }
        }
        return new ScheduleSeatsResult(layout, available);
    }
}
