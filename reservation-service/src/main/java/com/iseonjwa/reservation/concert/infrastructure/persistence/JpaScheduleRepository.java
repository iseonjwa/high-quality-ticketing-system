package com.iseonjwa.reservation.concert.infrastructure.persistence;

import com.iseonjwa.reservation.concert.domain.entity.Schedule;
import com.iseonjwa.reservation.concert.domain.repository.ScheduleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class JpaScheduleRepository implements ScheduleRepository {

    private final SpringDataScheduleRepository springDataScheduleRepository;

    @Override
    public Schedule save(Schedule schedule) {
        return springDataScheduleRepository.save(schedule);
    }

    @Override
    public Optional<Schedule> findById(Long id) {
        return springDataScheduleRepository.findById(id);
    }

    @Override
    public Optional<Schedule> findByIdForUpdate(Long id) {
        return springDataScheduleRepository.findByIdForUpdate(id);
    }

    @Override
    public List<Schedule> findUpcomingByConcertIds(Collection<Long> concertIds, Instant now) {
        return springDataScheduleRepository.findUpcomingByConcertIds(concertIds, now);
    }

    @Override
    public List<Schedule> findByConcertIdWithVenue(Long concertId) {
        return springDataScheduleRepository.findByConcertIdWithVenue(concertId);
    }

    @Override
    public List<Schedule> findOpenBy(Instant until, Instant now) {
        return springDataScheduleRepository.findOpenBy(until, now);
    }
}
