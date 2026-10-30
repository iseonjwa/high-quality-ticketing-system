package com.iseonjwa.reservation.seathold.infrastructure.persistence;

import com.iseonjwa.reservation.concert.domain.entity.Schedule;
import com.iseonjwa.reservation.concert.domain.entity.ScheduleSeat;
import com.iseonjwa.reservation.concert.domain.entity.ScheduleSeatStatus;
import com.iseonjwa.reservation.seathold.domain.repository.SeatHoldInventoryRepository;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

// concert 모듈에 Spring Data 저장소를 만들지 않으려고 EntityManager로 직접 조회한다
@Repository
public class JpaSeatHoldInventoryRepository implements SeatHoldInventoryRepository {
    private final EntityManager em;

    public JpaSeatHoldInventoryRepository(EntityManager em) {
        this.em = em;
    }

    @Override
    public Optional<Schedule> findSchedule(Long scheduleId) {
        return em.createQuery("SELECT s FROM Schedule s WHERE s.id = :id", Schedule.class)
                .setParameter("id", scheduleId)
                .getResultStream()
                .findFirst();
    }

    @Override
    public List<ScheduleSeat> findSeats(Long scheduleId, List<Long> seatIds) {
        return em.createQuery("SELECT s FROM ScheduleSeat s WHERE s.schedule.id = :scheduleId AND s.id IN :ids",
                        ScheduleSeat.class)
                .setParameter("scheduleId", scheduleId)
                .setParameter("ids", seatIds)
                .getResultList();
    }

    // Note: 변경 감지로 상태를 바꾸면 "읽을 때 AVAILABLE이었다"만 확인하고 덮어쓴다. 조건을 WHERE에 두면
    // 먼저 커밋한 요청이 행을 바꾼 뒤 기다리던 요청은 갱신 0행이 되어 같은 좌석을 두 번 잡지 못한다.
    @Override
    public int holdAvailableSeats(Long scheduleId, List<Long> seatIds, Instant now, Long actorId) {
        em.flush();
        return em.createQuery("""
                        UPDATE ScheduleSeat s
                           SET s.status = :held, s.updatedAt = :now, s.updatedBy = :actorId
                         WHERE s.schedule.id = :scheduleId
                           AND s.id IN :ids
                           AND s.status = :available
                        """)
                .setParameter("held", ScheduleSeatStatus.HELD)
                .setParameter("available", ScheduleSeatStatus.AVAILABLE)
                .setParameter("now", now)
                .setParameter("actorId", actorId)
                .setParameter("scheduleId", scheduleId)
                .setParameter("ids", seatIds)
                .executeUpdate();
    }
}
