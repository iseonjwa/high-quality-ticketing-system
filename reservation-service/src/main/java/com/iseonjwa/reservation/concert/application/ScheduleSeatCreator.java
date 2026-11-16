package com.iseonjwa.reservation.concert.application;

import com.iseonjwa.common.persistence.CurrentActorProvider;
import com.iseonjwa.common.response.BusinessException;
import com.iseonjwa.common.response.CommonErrorCode;
import com.iseonjwa.reservation.concert.domain.entity.Schedule;
import com.iseonjwa.reservation.concert.domain.entity.SeatGrade;
import com.iseonjwa.reservation.concert.domain.repository.ScheduleRepository;
import com.iseonjwa.reservation.concert.domain.repository.ScheduleSeatRepository;
import com.iseonjwa.reservation.concert.domain.repository.VenueSeatRepository;
import com.iseonjwa.reservation.global.vo.Money;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Set;

/**
 * 회차 좌석 생성의 DB 쓰기. 트랜잭션을 여기에만 두어, 부르는 쪽이 커밋이 끝난 뒤에 통계를 갱신할 수 있게 한다(ISE-284).
 */
@Component
@RequiredArgsConstructor
public class ScheduleSeatCreator {

    private final ScheduleRepository scheduleRepository;
    private final VenueSeatRepository venueSeatRepository;
    private final ScheduleSeatRepository scheduleSeatRepository;
    private final CurrentActorProvider currentActorProvider;

    // 공연장 좌석 배치를 그대로 복사하고 가격은 등급으로 정한다.
    // 가격은 공연장에 있는 등급마다 정확히 하나씩 있어야 한다(빠지면 가격 없는 좌석, 남으면 오타일 수 있다)
    @Transactional
    public int create(Long scheduleId, Map<SeatGrade, Money> prices) {
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
