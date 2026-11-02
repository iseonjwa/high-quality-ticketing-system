package com.iseonjwa.reservation.concert.infrastructure.persistence;

import com.iseonjwa.reservation.concert.domain.entity.Schedule;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

interface SpringDataScheduleRepository extends JpaRepository<Schedule, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Schedule s where s.id = :id")
    Optional<Schedule> findByIdForUpdate(@Param("id") Long id);

    @Query("select s from Schedule s where s.concert.id in :concertIds and s.startsAt > :now order by s.startsAt asc")
    List<Schedule> findUpcomingByConcertIds(@Param("concertIds") Collection<Long> concertIds,
                                            @Param("now") Instant now);

    // join fetch로 공연장을 한 쿼리에 같이 읽는다. 회차마다 공연장을 따로 조회하는 N+1을 막는다
    @Query("select s from Schedule s join fetch s.venue where s.concert.id = :concertId order by s.startsAt asc")
    List<Schedule> findByConcertIdWithVenue(@Param("concertId") Long concertId);
}
