package com.iseonjwa.reservation.concert.application;

import com.iseonjwa.common.response.BusinessException;
import com.iseonjwa.common.response.CommonErrorCode;
import com.iseonjwa.reservation.concert.domain.entity.Concert;
import com.iseonjwa.reservation.concert.domain.entity.Schedule;
import com.iseonjwa.reservation.concert.domain.repository.ConcertRepository;
import com.iseonjwa.reservation.concert.domain.repository.ScheduleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 공연 상세의 DB 읽기(ISE-285). 트랜잭션을 여기에만 두어 캐시에서 꺼내는 요청은 커넥션을 빌리지 않는다.
 * 좌석 조회의 {@link ScheduleSeatReader}(ISE-204)와 같은 이유다.
 */
@Component
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ConcertDetailReader {

    private final ConcertRepository concertRepository;
    private final ScheduleRepository scheduleRepository;

    // 1초 캐시가 비었거나 만료됐을 때만 부른다. 엔티티는 트랜잭션 안에서 값으로 바꿔 내보낸다
    public ConcertDetail read(Long concertId) {
        Concert concert = concertRepository.findById(concertId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.NOT_FOUND));
        List<Schedule> schedules = scheduleRepository.findByConcertIdWithVenue(concertId);
        return ConcertDetail.of(concert, schedules);
    }
}
