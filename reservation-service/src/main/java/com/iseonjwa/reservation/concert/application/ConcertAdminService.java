package com.iseonjwa.reservation.concert.application;

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
import com.iseonjwa.reservation.global.vo.Money;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;

/**
 * 운영자의 공연·회차 등록(SA 6-5, 11-5). created_by는 공통 감사 리스너가 요청의 사용자 ID로 채우고,
 * version은 JPA가 처음 저장할 때 0으로 채운다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConcertAdminService {

    private final ConcertRepository concertRepository;
    private final VenueRepository venueRepository;
    private final ScheduleRepository scheduleRepository;
    private final ScheduleSeatRepository scheduleSeatRepository;
    private final ScheduleSeatCreator scheduleSeatCreator;

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

    // 회차 좌석 생성. 좌석을 만들어 커밋한 뒤 회차 좌석 표의 DB 통계를 갱신한다(ISE-284)
    //
    // Note: 이 메서드에는 트랜잭션을 두지 않고 ScheduleSeatCreator에만 둔다. 같은 트랜잭션 안에서 갱신하면 좌석이
    // 아직 커밋 전이고, 커밋 직후 콜백(afterCommit)은 원래 커넥션을 아직 쥐고 있어 거기서 돌린 갱신이 커밋되지 않는다.
    // 커밋이 끝난 뒤 따로 부르면 커넥션도 한 번에 하나만 쓴다.
    public int createScheduleSeats(Long scheduleId, Map<SeatGrade, Money> prices) {
        int createdSeats = scheduleSeatCreator.create(scheduleId, prices);
        try {
            scheduleSeatRepository.refreshStatistics();
        } catch (DataAccessException e) {
            // 좌석은 이미 커밋됐다. 실패로 답하면 다시 보낸 요청이 409가 되므로 성공으로 답하고 남긴다.
            // 통계는 다음 자동 갱신 때 채워지고, 그 전까지 이 회차 좌석 조회는 느릴 수 있다
            log.warn("회차 좌석을 만든 뒤 DB 통계 갱신에 실패했습니다. scheduleId={}", scheduleId, e);
        }
        return createdSeats;
    }
}
