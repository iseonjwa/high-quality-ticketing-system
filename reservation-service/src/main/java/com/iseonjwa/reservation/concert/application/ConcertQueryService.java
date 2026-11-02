package com.iseonjwa.reservation.concert.application;

import com.iseonjwa.common.response.BusinessException;
import com.iseonjwa.common.response.CommonErrorCode;
import com.iseonjwa.reservation.concert.domain.entity.Concert;
import com.iseonjwa.reservation.concert.domain.entity.Schedule;
import com.iseonjwa.reservation.concert.domain.repository.ConcertRepository;
import com.iseonjwa.reservation.concert.domain.repository.ScheduleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 사용자의 공연 목록·상세 조회(SA 6-5, 11-5). 로그인 없이 호출된다.
 * 지운 공연·회차·공연장은 엔티티의 @SQLRestriction 때문에 조회되지 않는다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ConcertQueryService {

    private final ConcertRepository concertRepository;
    private final ScheduleRepository scheduleRepository;
    private final Clock clock;

    // 공연 목록
    public ConcertListResult getConcerts(int page, int size) {
        List<Concert> concerts = concertRepository.findPage(page, size);
        long totalElements = concertRepository.count();

        // 페이지에 있는 공연들의 남은 회차를 한 번에 읽고, 공연마다 가장 빠른 회차 하나만 남긴다
        List<Long> concertIds = concerts.stream().map(Concert::getId).toList();
        Map<Long, Schedule> nextSchedules = new HashMap<>();
        if (!concertIds.isEmpty()) {
            for (Schedule schedule : scheduleRepository.findUpcomingByConcertIds(concertIds, clock.instant())) {
                // 시작 시각 오름차순이라 공연마다 처음 나온 회차가 가장 빠르다
                nextSchedules.putIfAbsent(schedule.getConcert().getId(), schedule);
            }
        }
        return new ConcertListResult(concerts, nextSchedules, page, size, totalElements);
    }

    // 공연 상세
    public ConcertDetailResult getConcert(Long concertId) {
        Concert concert = concertRepository.findById(concertId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.NOT_FOUND));
        List<Schedule> schedules = scheduleRepository.findByConcertIdWithVenue(concertId);
        return new ConcertDetailResult(concert, schedules, clock.instant());
    }
}
