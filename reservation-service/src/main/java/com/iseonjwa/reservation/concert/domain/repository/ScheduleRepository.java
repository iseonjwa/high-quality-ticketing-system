package com.iseonjwa.reservation.concert.domain.repository;

import com.iseonjwa.reservation.concert.domain.entity.Schedule;

public interface ScheduleRepository {
    Schedule save(Schedule schedule);
}
