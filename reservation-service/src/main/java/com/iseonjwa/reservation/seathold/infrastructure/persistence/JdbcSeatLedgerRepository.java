package com.iseonjwa.reservation.seathold.infrastructure.persistence;

import com.iseonjwa.reservation.seathold.domain.repository.SeatLedgerRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

// 여러 테이블을 집계·외부 조인하는 대사 쿼리라 엔티티 대신 SQL로 쓴다. 상태 이름은 DB CHECK 제약 값과 같다
@Repository
public class JdbcSeatLedgerRepository implements SeatLedgerRepository {
    // 부분 유니크 인덱스(uq_reservation_seats_active_schedule_seat) 덕분에 한 좌석의 활성 예매 좌석은 많아야 한 건이다(D2)
    private static final String EXPECTED_SEAT_STATUS = """
            CASE WHEN rs.status = 'CONFIRMED' THEN 'SOLD'
                 WHEN rs.status = 'HELD' THEN 'HELD'
                 ELSE 'AVAILABLE' END""";

    private final JdbcTemplate jdbc;

    public JdbcSeatLedgerRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<SeatStatusMismatch> findSeatStatusMismatches(Long scheduleId, int limit) {
        return jdbc.query("""
                        SELECT ss.id, ss.status, %s AS expected
                          FROM schedule_seats ss
                          LEFT JOIN reservation_seats rs
                            ON rs.schedule_seat_id = ss.id AND rs.status IN ('HELD', 'CONFIRMED')
                         WHERE ss.schedule_id = ?
                           AND ss.status <> %s
                         ORDER BY ss.id
                         LIMIT ?
                        """.formatted(EXPECTED_SEAT_STATUS, EXPECTED_SEAT_STATUS),
                (rs, rowNum) -> new SeatStatusMismatch(rs.getLong("id"), rs.getString("status"),
                        rs.getString("expected")),
                scheduleId, limit);
    }

    @Override
    public List<HeldCountMismatch> findHeldCountMismatches(Long scheduleId, int limit) {
        return jdbc.query("""
                        WITH active AS (
                            SELECT r.user_id, count(*) AS seat_count
                              FROM reservation_seats rs
                              JOIN reservations r ON r.id = rs.reservation_id
                             WHERE r.schedule_id = ? AND rs.status IN ('HELD', 'CONFIRMED')
                             GROUP BY r.user_id
                        ), quota AS (
                            SELECT user_id, held_count FROM user_schedule_quotas WHERE schedule_id = ?
                        )
                        SELECT COALESCE(q.user_id, a.user_id) AS user_id,
                               COALESCE(q.held_count, 0) AS actual,
                               COALESCE(a.seat_count, 0) AS expected
                          FROM quota q
                          FULL JOIN active a ON a.user_id = q.user_id
                         WHERE COALESCE(q.held_count, 0) <> COALESCE(a.seat_count, 0)
                         ORDER BY 1
                         LIMIT ?
                        """,
                (rs, rowNum) -> new HeldCountMismatch(rs.getLong("user_id"), rs.getInt("actual"),
                        rs.getInt("expected")),
                scheduleId, scheduleId, limit);
    }

    @Override
    public Optional<String> lockSeatStatus(Long scheduleId, Long scheduleSeatId) {
        return jdbc.queryForList("SELECT status FROM schedule_seats WHERE id = ? AND schedule_id = ? FOR UPDATE",
                String.class, scheduleSeatId, scheduleId).stream().findFirst();
    }

    @Override
    public String expectedSeatStatus(Long scheduleSeatId) {
        // 활성 예매 좌석이 없어도 한 행을 돌려주도록 회차 좌석에서 출발한다
        return jdbc.queryForObject("""
                SELECT %s
                  FROM schedule_seats ss
                  LEFT JOIN reservation_seats rs
                    ON rs.schedule_seat_id = ss.id AND rs.status IN ('HELD', 'CONFIRMED')
                 WHERE ss.id = ?
                """.formatted(EXPECTED_SEAT_STATUS), String.class, scheduleSeatId);
    }

    @Override
    public int updateSeatStatus(Long scheduleSeatId, String expected, Instant now, Long actorId) {
        return jdbc.update("UPDATE schedule_seats SET status = ?, updated_at = ?, updated_by = ?"
                + " WHERE id = ? AND status <> ?", expected, Timestamp.from(now), actorId, scheduleSeatId, expected);
    }

    @Override
    public int lockHeldCount(Long userId, Long scheduleId, Instant now, Long actorId) {
        jdbc.update("""
                INSERT INTO user_schedule_quotas (user_id, schedule_id, held_count, created_at, created_by,
                                                  updated_at, updated_by)
                VALUES (?, ?, 0, ?, ?, ?, ?)
                ON CONFLICT (user_id, schedule_id) DO NOTHING
                """, userId, scheduleId, Timestamp.from(now), actorId, Timestamp.from(now), actorId);
        return jdbc.queryForObject("SELECT held_count FROM user_schedule_quotas"
                + " WHERE user_id = ? AND schedule_id = ? FOR UPDATE", Integer.class, userId, scheduleId);
    }

    @Override
    public int countActiveSeats(Long userId, Long scheduleId) {
        return jdbc.queryForObject("""
                SELECT count(*)
                  FROM reservation_seats rs
                  JOIN reservations r ON r.id = rs.reservation_id
                 WHERE r.user_id = ? AND r.schedule_id = ? AND rs.status IN ('HELD', 'CONFIRMED')
                """, Integer.class, userId, scheduleId);
    }

    @Override
    public int updateHeldCount(Long userId, Long scheduleId, int expected, Instant now, Long actorId) {
        return jdbc.update("UPDATE user_schedule_quotas SET held_count = ?, updated_at = ?, updated_by = ?"
                + " WHERE user_id = ? AND schedule_id = ? AND held_count <> ?",
                expected, Timestamp.from(now), actorId, userId, scheduleId, expected);
    }
}
