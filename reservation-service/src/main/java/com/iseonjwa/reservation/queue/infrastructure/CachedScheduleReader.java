package com.iseonjwa.reservation.queue.infrastructure;

import com.iseonjwa.reservation.concert.domain.repository.ScheduleRepository;
import com.iseonjwa.reservation.queue.domain.ScheduleReader;
import com.iseonjwa.reservation.queue.domain.ScheduleTimes;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Component
public class CachedScheduleReader implements ScheduleReader {

    private volatile List<ScheduleTimes> upcoming = List.of();
    private final ScheduleRepository scheduleRepository;
    private final Clock clock;
    private final Duration lookAhead;


    public CachedScheduleReader(ScheduleRepository scheduleRepository,
                                @Qualifier("commonAuditingClock") Clock clock,
                                @Value("${queue.schedule-cache-look-ahead:1h}") Duration lookAhead) {
        this.scheduleRepository = scheduleRepository;
        this.clock = clock;
        this.lookAhead = lookAhead;
    }


    // 메모리에 있는 ScheduleTimes 정보인 경우에는 바로 주고, 아니면 DB에서 가져옴
    @Override
    public Optional<ScheduleTimes> findById(long id) {
        return upcoming.stream()
                .filter(scheduleTimes -> scheduleTimes.id() == id)
                .findFirst()
                .or(() -> scheduleRepository.findById(id)
                        .map(schedule -> new ScheduleTimes(schedule.getId(), schedule.getOpenAt(), schedule.getStartsAt())));
    }

    @Override
    public List<ScheduleTimes> findOnSale() {
        Instant now = clock.instant();
        return upcoming.stream()
                .filter(scheduleTimes -> scheduleTimes.isOnSale(now)) // 현재 열려있는 회차의 시각 정보만 반환
                .toList();
    }


    // 30초마다 DB에서 현재~1시간 뒤 안에 열려있는 회차들 목록을 가져와서 메모리에 캐시함
    @Scheduled(fixedDelayString = "${queue.schedule-cache-refresh:30s}")
    void refresh() {
        Instant now = clock.instant();
        upcoming = scheduleRepository.findOpenBy(now.plus(lookAhead), now).stream()
                .map(schedule -> new ScheduleTimes(schedule.getId(), schedule.getOpenAt(), schedule.getStartsAt()))
                .toList();
    }
}
