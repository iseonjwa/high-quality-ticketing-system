package com.iseonjwa.reservation.concert.application;

import com.iseonjwa.common.persistence.CurrentActorProvider;
import com.iseonjwa.common.response.BusinessException;
import com.iseonjwa.common.response.CommonErrorCode;
import com.iseonjwa.reservation.concert.domain.entity.Concert;
import com.iseonjwa.reservation.concert.domain.entity.Schedule;
import com.iseonjwa.reservation.concert.domain.entity.SeatGrade;
import com.iseonjwa.reservation.concert.domain.entity.Venue;
import com.iseonjwa.reservation.concert.domain.repository.ConcertRepository;
import com.iseonjwa.reservation.concert.domain.repository.ScheduleRepository;
import com.iseonjwa.reservation.concert.domain.repository.ScheduleSeatRepository;
import com.iseonjwa.reservation.concert.domain.repository.VenueRepository;
import com.iseonjwa.reservation.concert.domain.repository.VenueSeatRepository;
import com.iseonjwa.reservation.global.vo.Money;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

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
    private final VenueSeatRepository venueSeatRepository;
    private final ScheduleSeatRepository scheduleSeatRepository;
    private final CurrentActorProvider currentActorProvider;

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

    // 회차 좌석 생성. 공연장 좌석 배치를 그대로 복사하고 가격은 등급으로 정한다.
    // 가격은 공연장에 있는 등급마다 정확히 하나씩 있어야 한다(빠지면 가격 없는 좌석, 남으면 오타일 수 있다)
    @Transactional
    public int createScheduleSeats(Long scheduleId, Map<SeatGrade, Money> prices) {
        Schedule schedule = scheduleRepository.findByIdForUpdate(scheduleId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.NOT_FOUND));
        if (scheduleSeatRepository.existsByScheduleId(scheduleId)) {
            throw new BusinessException(ConcertErrorCode.SCHEDULE_SEATS_ALREADY_CREATED);
        }

        Long venueId = schedule.getVenue().getId();
        Set<SeatGrade> venueGrades = venueSeatRepository.findGradesByVenueId(venueId);
        if (!prices.keySet().equals(venueGrades)) {
            throw new BusinessException(CommonErrorCode.VALIDATION_FAILED);
        }

        Long actorId = currentActorProvider.currentActorId().orElse(null);
        return scheduleSeatRepository.createFromVenueSeats(scheduleId, venueId, prices, actorId);
    }
}
