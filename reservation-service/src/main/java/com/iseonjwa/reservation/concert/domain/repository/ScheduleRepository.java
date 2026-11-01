package com.iseonjwa.reservation.concert.domain.repository;

import com.iseonjwa.reservation.concert.domain.entity.Schedule;

import java.util.Optional;

public interface ScheduleRepository {
    Schedule save(Schedule schedule);

    // 회차 행에 쓰기 잠금(SELECT ... FOR UPDATE)을 건다. 같은 회차에 좌석 생성이 동시에 두 번 들어오면
    // 뒤 요청은 앞 트랜잭션이 끝날 때까지 기다렸다가 "이미 만들었다"를 본다
    Optional<Schedule> findByIdForUpdate(Long id);
}
