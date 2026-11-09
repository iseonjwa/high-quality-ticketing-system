package com.iseonjwa.reservation.concert.application;

import com.iseonjwa.common.response.BusinessException;
import com.iseonjwa.common.response.CommonErrorCode;
import com.iseonjwa.reservation.concert.domain.repository.ScheduleRepository;
import com.iseonjwa.reservation.concert.domain.repository.ScheduleSeatRepository;
import com.iseonjwa.reservation.concert.domain.repository.ScheduleSeatRepository.SeatLayoutRow;
import com.iseonjwa.reservation.concert.domain.repository.ScheduleSeatRepository.SeatMapRow;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 좌석 조회 경량화(P4)의 DB 읽기. 트랜잭션(커넥션을 쥐는 구간)을 여기에만 둔다.
 *
 * public인 이유: 트랜잭션 프록시가 public 메서드에만 확실히 걸리게 한다.
 *
 * Note: 캐시를 고르는 ScheduleSeatQueryService에 트랜잭션을 두면 캐시에서 답하는 요청도 커넥션을 먼저 받는다.
 * readOnly 트랜잭션은 시작할 때 커넥션을 읽기 전용으로 바꾸려고 바로 빌려 오기 때문이다.
 */
@Component
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SeatMapReader {

    private final ScheduleRepository scheduleRepository;
    private final ScheduleSeatRepository scheduleSeatRepository;

    public List<SeatMapRow> readRows(Long scheduleId) {
        requireSchedule(scheduleId);
        return scheduleSeatRepository.findSeatMapRows(scheduleId);
    }

    public List<SeatLayoutRow> readLayout(Long scheduleId) {
        requireSchedule(scheduleId);
        return scheduleSeatRepository.findSeatLayout(scheduleId);
    }

    // 회차가 지워졌으면 상태를 읽을 때 404가 난다. 배치를 캐시해 두어도 지운 회차를 계속 보여 주지 않는다
    public long[] readHoldableSeatIds(Long scheduleId) {
        requireSchedule(scheduleId);
        return scheduleSeatRepository.findHoldableSeatIds(scheduleId);
    }

    private void requireSchedule(Long scheduleId) {
        if (scheduleRepository.findById(scheduleId).isEmpty()) {
            throw new BusinessException(CommonErrorCode.NOT_FOUND);
        }
    }
}
