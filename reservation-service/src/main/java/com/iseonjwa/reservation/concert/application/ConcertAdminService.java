package com.iseonjwa.reservation.concert.application;

import com.iseonjwa.common.response.BusinessException;
import com.iseonjwa.common.response.CommonErrorCode;
import com.iseonjwa.reservation.concert.domain.entity.Concert;
import com.iseonjwa.reservation.concert.domain.entity.Schedule;
import com.iseonjwa.reservation.concert.domain.entity.Venue;
import com.iseonjwa.reservation.concert.domain.repository.ConcertRepository;
import com.iseonjwa.reservation.concert.domain.repository.ScheduleRepository;
import com.iseonjwa.reservation.concert.domain.repository.VenueRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * 운영자의 공연·회차 등록(SA 6-5, 11-5). created_by는 공통 감사 리스너가 요청의 사용자 ID로 채우고,
 * version은 JPA가 처음 저장할 때 0으로 채운다.
 */
@Service
@RequiredArgsConstructor
public class ConcertAdminService {

    private final ConcertRepository concertRepository;
    private final VenueRepository venueRepository;
    private final ScheduleRepository scheduleRepository;

    // 공연 등록
    @Transactional
    public Long registerConcert(String title, String description) {
        Concert concert = concertRepository.save(new Concert(title, description));
        return concert.getId();
    }

    // 회차 등록. 지운 공연·공연장은 조회되지 않아 없는 것과 같은 404다
    @Transactional
    public Long registerSchedule(Long concertId, Long venueId, Instant startsAt, Instant openAt,
                                 Integer maxPerUser, Integer holdMinutes) {
        Concert concert = concertRepository.findById(concertId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.NOT_FOUND));
        Venue venue = venueRepository.findById(venueId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.NOT_FOUND));

        Schedule schedule = Schedule.create(concert, venue, startsAt, openAt, maxPerUser, holdMinutes);
        return scheduleRepository.save(schedule).getId();
    }
}
