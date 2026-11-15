package com.iseonjwa.reservation.seathold.infrastructure.persistence;

import com.iseonjwa.reservation.seathold.domain.repository.SeatHoldQuickCheckReader;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Note: 엔티티를 만들지 않고 자동 커밋 한 문장으로 항상 한 행을 돌려준다. 좌석 조건(status='AVAILABLE',
 * grade<>'LOTTERY')은 ScheduleSeat.isHoldable·holdAvailableSeats와 같다. 거절 상태는 부분 유니크 인덱스
 * (status <> 'REJECTED')를 쓰도록 리터럴로 적는다. 좌석은 회차에 속한 것만 센다(지운 좌석 조건은 기존 조회처럼 두지 않는다)
 */
@Repository
public class JdbcSeatHoldQuickCheckReader implements SeatHoldQuickCheckReader {
    private static final String SQL = """
            SELECT EXISTS (SELECT 1 FROM seat_hold_requests WHERE request_id = ?) AS request_exists,
                   (CAST(? AS VARCHAR) IS NOT NULL
                    AND EXISTS (SELECT 1 FROM seat_hold_requests
                                 WHERE entry_token_jti = CAST(? AS VARCHAR) AND status <> 'REJECTED')) AS token_active,
                   sc.id IS NOT NULL AS schedule_exists,
                   sc.max_per_user, sc.open_at, sc.starts_at,
                   (SELECT count(*) FROM schedule_seats s
                     WHERE s.schedule_id = ? AND s.id = ANY (?)) AS seats_found,
                   (SELECT count(*) FROM schedule_seats s
                     WHERE s.schedule_id = ? AND s.id = ANY (?)
                       AND s.status = 'AVAILABLE' AND s.grade <> 'LOTTERY') AS seats_holdable
              FROM (SELECT 1) AS one
              LEFT JOIN schedules sc ON sc.id = ? AND sc.deleted_at IS NULL
            """;

    private final JdbcTemplate jdbc;

    public JdbcSeatHoldQuickCheckReader(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public QuickCheck read(UUID requestId, String entryTokenJti, Long scheduleId, List<Long> seatIds) {
        return jdbc.query(connection -> {
            var statement = connection.prepareStatement(SQL);
            var ids = connection.createArrayOf("bigint", seatIds.toArray());
            statement.setObject(1, requestId);
            statement.setString(2, entryTokenJti);
            statement.setString(3, entryTokenJti);
            statement.setLong(4, scheduleId);
            statement.setArray(5, ids);
            statement.setLong(6, scheduleId);
            statement.setArray(7, ids);
            statement.setLong(8, scheduleId);
            return statement;
        }, resultSet -> {
            resultSet.next();
            var scheduleExists = resultSet.getBoolean("schedule_exists");
            return new QuickCheck(resultSet.getBoolean("request_exists"), resultSet.getBoolean("token_active"),
                    scheduleExists, scheduleExists ? resultSet.getInt("max_per_user") : 0,
                    instant(resultSet.getObject("open_at", OffsetDateTime.class)),
                    instant(resultSet.getObject("starts_at", OffsetDateTime.class)),
                    resultSet.getInt("seats_found"), resultSet.getInt("seats_holdable"));
        });
    }

    // Note: TIMESTAMPTZ는 OffsetDateTime으로 읽는다. getTimestamp는 JVM 기본 시간대 변환이 끼어 판매 기간 경계가 어긋날 수 있다
    private static Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}
