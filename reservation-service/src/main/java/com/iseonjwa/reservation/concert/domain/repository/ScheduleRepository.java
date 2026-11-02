package com.iseonjwa.reservation.concert.domain.repository;

import com.iseonjwa.reservation.concert.domain.entity.Schedule;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ScheduleRepository {
    Schedule save(Schedule schedule);

    // 회차 행에 쓰기 잠금(SELECT ... FOR UPDATE)을 건다. 같은 회차에 좌석 생성이 동시에 두 번 들어오면
    // 뒤 요청은 앞 트랜잭션이 끝날 때까지 기다렸다가 "이미 만들었다"를 본다
    Optional<Schedule> findByIdForUpdate(Long id);

    // 여러 공연의 아직 시작하지 않은 회차. 시작 시각 오름차순이다
    List<Schedule> findUpcomingByConcertIds(Collection<Long> concertIds, Instant now);

    // 한 공연의 회차 전부(공연장 이름을 같이 읽는다). 시작 시각 오름차순이다
    List<Schedule> findByConcertIdWithVenue(Long concertId);
}
