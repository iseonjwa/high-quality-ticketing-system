package com.iseonjwa.reservation.concert.infrastructure.persistence;

import com.iseonjwa.reservation.concert.domain.entity.Schedule;
import com.iseonjwa.reservation.concert.domain.repository.ScheduleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class JpaScheduleRepository implements ScheduleRepository {

    private final SpringDataScheduleRepository springDataScheduleRepository;

    @Override
    public Schedule save(Schedule schedule) {
        return springDataScheduleRepository.save(schedule);
    }
}
