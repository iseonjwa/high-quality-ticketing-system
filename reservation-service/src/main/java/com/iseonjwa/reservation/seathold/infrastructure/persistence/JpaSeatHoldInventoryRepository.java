package com.iseonjwa.reservation.seathold.infrastructure.persistence;

import com.iseonjwa.reservation.concert.domain.entity.Schedule;
import com.iseonjwa.reservation.concert.domain.entity.ScheduleSeat;
import com.iseonjwa.reservation.concert.domain.entity.ScheduleSeatStatus;
import com.iseonjwa.reservation.concert.domain.entity.SeatGrade;
import com.iseonjwa.reservation.seathold.domain.repository.SeatHoldInventoryRepository;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Repository;

import java.time.Duration;
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

    // set_config(..., true)는 SET LOCAL과 같다. 트랜잭션이 끝나면 원래 값으로 돌아가 커넥션 풀의 다른 요청에 남지 않는다
    @Override
    public void applyLockTimeout(Duration timeout) {
        em.createNativeQuery("SELECT set_config('lock_timeout', :timeout, true)")
                .setParameter("timeout", timeout.toMillis() + "ms")
                .getSingleResult();
    }

    // Note: 변경 감지로 상태를 바꾸면 "읽을 때 AVAILABLE이었다"만 확인하고 덮어쓴다. 조건을 WHERE에 두면
    // 먼저 커밋한 요청이 행을 바꾼 뒤 기다리던 요청은 갱신 0행이 되어 같은 좌석을 두 번 잡지 못한다.
    // 추첨석(LOTTERY)도 같은 WHERE에서 빼서, 요청에 섞이면 이미 잡힌 좌석처럼 409가 된다(추첨 D9).
    // 잠금은 하위 쿼리의 ORDER BY id FOR UPDATE로 좌석 ID 오름차순을 명시한다(SA 12-7 ②). 한 문장 UPDATE도 같은 계획이면
    // 순서가 같지만, 실행 계획이 바뀌어도 순서가 흔들리지 않게 문장에 적는다
    @Override
    public int holdAvailableSeats(Long scheduleId, List<Long> seatIds, Instant now, Long actorId) {
        em.flush();
        return em.createNativeQuery("""
                        UPDATE schedule_seats
                           SET status = 'HELD', updated_at = :now, updated_by = :actorId
                         WHERE id IN (SELECT id FROM schedule_seats
                                       WHERE schedule_id = :scheduleId
                                         AND id IN (:ids)
                                         AND status = 'AVAILABLE'
                                         AND grade <> 'LOTTERY'
                                       ORDER BY id
                                         FOR UPDATE)
                        """)
                .setParameter("now", now)
                .setParameter("actorId", actorId)
                .setParameter("scheduleId", scheduleId)
                .setParameter("ids", seatIds)
                .executeUpdate();
    }

    // Note: 자동 배정은 사용자들이 같은 구역의 앞 ID로 몰린다. 그냥 FOR UPDATE면 앞 사람의 커밋을 줄 서서 기다리고
    // lock_timeout(500ms)을 넘기면 409가 된다. SKIP LOCKED는 잠긴 행을 건너뛰고 다음 빈 좌석을 잠가 대기와 충돌을 함께 없앤다.
    // PostgreSQL은 LIMIT 아래에서 행을 잠그므로, 건너뛰거나 다시 검사에서 빠진 행만큼 더 읽어 limit을 채운다.
    // ORDER BY id는 일반 선점과 같은 좌석 ID 오름차순 잠금 순서(SA 12-7 ②)이고, 좌석 ID가 구역·열·번호 순으로 만들어져
    // 2매가 대개 같은 열의 붙은 자리가 된다. 건너뛴 행이나 열 끝에서는 떨어질 수 있다(연석 보장 안 함, ADR 0059).
    // 조건(AVAILABLE, LOTTERY 아님)은 holdAvailableSeats와 같다. 잠근 뒤 상태 전이는 그 조건부 UPDATE가 한다
    @Override
    @SuppressWarnings("unchecked")
    public List<Long> lockAvailableSeatIds(Long scheduleId, SeatGrade grade, String section, int limit) {
        em.flush();
        List<Number> ids = em.createNativeQuery("""
                        SELECT id FROM schedule_seats
                         WHERE schedule_id = :scheduleId
                           AND status = 'AVAILABLE'
                           AND grade <> 'LOTTERY'
                           AND (CAST(:grade AS varchar) IS NULL OR grade = CAST(:grade AS varchar))
                           AND (CAST(:section AS varchar) IS NULL OR section = CAST(:section AS varchar))
                         ORDER BY id
                         LIMIT :limit
                           FOR UPDATE SKIP LOCKED
                        """)
                .setParameter("scheduleId", scheduleId)
                .setParameter("grade", grade == null ? null : grade.name())
                .setParameter("section", section)
                .setParameter("limit", limit)
                .getResultList();
        return ids.stream().map(Number::longValue).toList();
    }

    // 일반 선점과 같은 조건부 UPDATE에서 등급 조건만 반대로 둔다. 추첨석이 아닌 좌석이 섞이면 갱신 행 수가 모자란다
    @Override
    public int holdAvailableLotterySeats(Long scheduleId, List<Long> seatIds, Instant now, Long actorId) {
        em.flush();
        return em.createQuery("""
                        UPDATE ScheduleSeat s
                           SET s.status = :held, s.updatedAt = :now, s.updatedBy = :actorId
                         WHERE s.schedule.id = :scheduleId
                           AND s.id IN :ids
                           AND s.status = :available
                           AND s.grade = :lottery
                        """)
                .setParameter("held", ScheduleSeatStatus.HELD)
                .setParameter("lottery", SeatGrade.LOTTERY)
                .setParameter("available", ScheduleSeatStatus.AVAILABLE)
                .setParameter("now", now)
                .setParameter("actorId", actorId)
                .setParameter("scheduleId", scheduleId)
                .setParameter("ids", seatIds)
                .executeUpdate();
    }

    @Override
    public int releaseHeldSeats(Long scheduleId, List<Long> seatIds, Instant now, Long actorId) {
        em.flush();
        return em.createQuery("""
                        UPDATE ScheduleSeat s
                           SET s.status = :available, s.updatedAt = :now, s.updatedBy = :actorId
                         WHERE s.schedule.id = :scheduleId
                           AND s.id IN :ids
                           AND s.status = :held
                        """)
                .setParameter("available", ScheduleSeatStatus.AVAILABLE)
                .setParameter("held", ScheduleSeatStatus.HELD)
                .setParameter("now", now)
                .setParameter("actorId", actorId)
                .setParameter("scheduleId", scheduleId)
                .setParameter("ids", seatIds)
                .executeUpdate();
    }
}
