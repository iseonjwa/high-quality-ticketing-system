package com.iseonjwa.reservation.concert.application;

import com.iseonjwa.common.response.BusinessException;
import com.iseonjwa.common.response.CommonErrorCode;
import com.iseonjwa.reservation.concert.domain.repository.ScheduleRepository;
import com.iseonjwa.reservation.concert.domain.repository.ScheduleSeatRepository;
import com.iseonjwa.reservation.concert.domain.repository.SeatLayoutRow;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 좌석 조회의 DB 읽기. 트랜잭션(커넥션을 쥐는 구간)을 여기에만 둔다(ISE-204).
 *
 * <p>Note: 조회 서비스 전체에 트랜잭션을 두면 캐시에서 배치를 꺼내고 15,000석의 선점 가능 여부를 맞추는 동안에도
 * 커넥션을 쥔다. readOnly 트랜잭션은 시작할 때 커넥션을 바로 빌려 오기 때문이다. 커넥션 풀(5개)이 좌석 조회의
 * 병목이라 DB를 읽는 동안만 쥐게 나눴다.
 */
@Component
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ScheduleSeatReader {

    private final ScheduleRepository scheduleRepository;
    private final ScheduleSeatRepository scheduleSeatRepository;

    // 회차 좌석 배치(구역 → 열 → 번호 순). 캐시가 비었을 때만 부른다
    public List<SeatLayoutRow> readLayout(Long scheduleId) {
        requireSchedule(scheduleId);
        return scheduleSeatRepository.findSeatLayouts(scheduleId);
    }

    // 일반 선점이 잡을 수 있는 좌석 ID. 1초 캐시가 만료됐을 때 부른다.
    // 배치를 캐시해 두어도 회차를 지우면 여기서 404가 나서 지운 회차를 1초 넘게 보여 주지 않는다
    public List<Long> readHoldableSeatIds(Long scheduleId) {
        requireSchedule(scheduleId);
        return scheduleSeatRepository.findHoldableSeatIds(scheduleId);
    }

    private void requireSchedule(Long scheduleId) {
        if (scheduleRepository.findById(scheduleId).isEmpty()) {
            throw new BusinessException(CommonErrorCode.NOT_FOUND);
        }
    }
}
