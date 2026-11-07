package com.iseonjwa.reservation.concert.application;

import com.iseonjwa.common.response.BusinessException;
import com.iseonjwa.common.response.CommonErrorCode;
import com.iseonjwa.reservation.concert.domain.entity.ScheduleSeat;
import com.iseonjwa.reservation.concert.domain.repository.ScheduleRepository;
import com.iseonjwa.reservation.concert.domain.repository.ScheduleSeatRepository;
import com.iseonjwa.reservation.concert.domain.repository.ScheduleSeatRepository.SeatMapRow;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 회차 좌석 조회(SA 6-5, 11장). 로그인과 입장 토큰은 Gateway가 검증하므로 여기서 다시 보지 않는다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ScheduleSeatQueryService {

    private final ScheduleRepository scheduleRepository;
    private final ScheduleSeatRepository scheduleSeatRepository;

    // 회차 좌석 배치를 엔티티로 읽는다(P4a 전 경로). 측정 브랜치에서는 P4a 응답과 JSON이 같은지 보는 테스트만 쓴다
    public List<ScheduleSeat> getSeats(Long scheduleId) {
        requireSchedule(scheduleId);
        return scheduleSeatRepository.findByScheduleIdWithVenueSeat(scheduleId);
    }

    // P4a: 응답에 필요한 열만 행으로 읽는다. 구역 → 열 → 번호 순이다
    // Note: 15,000석을 엔티티로 읽으면 엔티티와 변경 감지용 스냅숏을 커넥션을 쥔 채 그만큼 만든다
    public List<SeatMapRow> getSeatMap(Long scheduleId) {
        requireSchedule(scheduleId);
        return scheduleSeatRepository.findSeatMapRows(scheduleId);
    }

    private void requireSchedule(Long scheduleId) {
        if (scheduleRepository.findById(scheduleId).isEmpty()) {
            throw new BusinessException(CommonErrorCode.NOT_FOUND);
        }
    }
}
