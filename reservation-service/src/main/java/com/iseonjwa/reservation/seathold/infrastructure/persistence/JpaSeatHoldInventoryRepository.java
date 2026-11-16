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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

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

    // Note: 지운 회차 조건을 직접 적는다. Schedule의 @SQLRestriction이 연관 JOIN FETCH에도 붙는지에 기대지 않는다
    @Override
    public List<ScheduleSeat> findSeatsWithSchedule(Long scheduleId, List<Long> seatIds) {
        return em.createQuery("""
                        SELECT s FROM ScheduleSeat s JOIN FETCH s.schedule sc
                         WHERE sc.id = :scheduleId AND sc.deletedAt IS NULL AND s.id IN :ids
                        """, ScheduleSeat.class)
                .setParameter("scheduleId", scheduleId)
                .setParameter("ids", seatIds)
                .getResultList();
    }

    // Note: 선택절 하위 쿼리로 멱등키 행 수와 활성 토큰 행 수를 좌석과 같은 문장에서 읽는다. 같은 시점의 데이터라
    // "멱등키는 아직 안 보이는데 그 요청이 잡은 좌석만 보이는" 일이 없다(동기형은 둘을 한 트랜잭션에 커밋하고,
    // 접수형은 멱등키를 먼저 커밋한다). jti가 null이면 비교가 참이 되지 않아 토큰 수는 0이다.
    // Note: 거절 상태는 파라미터가 아니라 리터럴로 쓴다. 부분 유니크 인덱스(status <> 'REJECTED')는 조건이 리터럴이어야
    // 계획기가 쓸 수 있다고 증명한다. 파라미터면 서버 쪽 일반 계획에서 인덱스를 못 써 순차 스캔이 될 수 있다
    @Override
    public HoldPrecheck precheck(Long scheduleId, List<Long> seatIds, UUID requestId, String entryTokenJti) {
        List<Object[]> rows = em.createQuery("""
                        SELECT s,
                               (SELECT count(r) FROM SeatHoldRequest r WHERE r.requestId = :requestId),
                               (SELECT count(t) FROM SeatHoldRequest t
                                 WHERE t.entryTokenJti = :jti
                                   AND t.status <> com.iseonjwa.reservation.seathold.domain.entity.SeatHoldRequestStatus.REJECTED)
                          FROM ScheduleSeat s JOIN FETCH s.schedule sc
                         WHERE sc.id = :scheduleId AND sc.deletedAt IS NULL AND s.id IN :ids
                        """, Object[].class)
                .setParameter("requestId", requestId)
                .setParameter("jti", entryTokenJti)
                .setParameter("scheduleId", scheduleId)
                .setParameter("ids", seatIds)
                .getResultList();
        if (rows.isEmpty()) {
            return new HoldPrecheck(List.of(), false, false);
        }
        var seats = new ArrayList<ScheduleSeat>(rows.size());
        for (var row : rows) {
            seats.add((ScheduleSeat) row[0]);
        }
        var first = rows.getFirst();
        return new HoldPrecheck(seats, ((Number) first[1]).longValue() > 0, ((Number) first[2]).longValue() > 0);
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
    // Note: SKIP LOCKED는 쓰지 않는다. 여러 좌석 요청은 일부 좌석을 잠갔다가 다른 좌석 때문에 롤백하는데, 그 짧은 잠금까지
    // 건너뛰어 비어 있는 좌석에 409를 준다. 동시성 테스트 C2에서 성공한 선점이 약 1/3 줄었다(ADR 0061, TS-73).
    // 대신 잠금 대기 상한을 짧게 둔다(SyncSeatHoldService.LOCK_TIMEOUT)
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
    // Note: 등급·구역 조건은 받은 것만 SQL에 넣는다. ":p IS NULL OR col = :p" 모양은 드라이버가 준비된 문장을
    // 일반 계획으로 바꾸면 인덱스 조건이 되지 못해 pk(id)를 처음부터 훑는다(TS-72). 회차 안 ID 순 탐색은
    // (schedule_id, id)·(schedule_id, section, id)·(schedule_id, grade, id) 인덱스가 맡는다(V20261116_6)
    @Override
    @SuppressWarnings("unchecked")
    public List<Long> lockAvailableSeatIds(Long scheduleId, SeatGrade grade, String section, int limit) {
        em.flush();
        StringBuilder sql = new StringBuilder("""
                SELECT id FROM schedule_seats
                 WHERE schedule_id = :scheduleId
                   AND status = 'AVAILABLE'
                   AND grade <> 'LOTTERY'
                """);
        if (grade != null) {
            sql.append("   AND grade = :grade\n");
        }
        if (section != null) {
            sql.append("   AND section = :section\n");
        }
        sql.append(" ORDER BY id\n LIMIT :limit\n   FOR UPDATE SKIP LOCKED");
        var query = em.createNativeQuery(sql.toString())
                .setParameter("scheduleId", scheduleId)
                .setParameter("limit", limit);
        if (grade != null) {
            query.setParameter("grade", grade.name());
        }
        if (section != null) {
            query.setParameter("section", section);
        }
        List<Number> ids = query.getResultList();
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
