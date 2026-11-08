package com.iseonjwa.reservation.seathold.concurrency;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 동시성 시나리오의 데이터 준비·정리·검증 쿼리. 라운드마다 새 회차를 만들어 앞 라운드의 좌석·매수가 섞이지 않게 한다.
 * 검증 쿼리는 대사(ISE-14) 기준과 같게 reservation_seats를 판매 여부의 기준으로 본다(D2).
 */
final class SeatHoldFixture {
    static final String CONCERT_TITLE = "ISE-79 동시성 테스트";
    private static final long SEAT_PRICE = 165000L;

    private final JdbcTemplate jdbc;
    private final List<Long> createdVenueIds = new ArrayList<>();

    SeatHoldFixture(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    Long createOnSaleSchedule() {
        var venueId = jdbc.queryForObject("INSERT INTO venues (name, created_at, updated_at)"
                + " VALUES ('ISE-79 테스트 공연장', now(), now()) RETURNING id", Long.class);
        createdVenueIds.add(venueId);
        var concertId = jdbc.queryForObject("INSERT INTO concerts (title, version, created_at, updated_at)"
                + " VALUES (?, 0, now(), now()) RETURNING id", Long.class, CONCERT_TITLE);
        return jdbc.queryForObject("INSERT INTO schedules (concert_id, venue_id, starts_at, open_at, version,"
                + " created_at, updated_at) VALUES (?, ?, now() + interval '1 day', now() - interval '1 minute',"
                + " 0, now(), now()) RETURNING id", Long.class, concertId, venueId);
    }

    List<Long> createSeats(Long scheduleId, int count) {
        var venueId = jdbc.queryForObject("SELECT venue_id FROM schedules WHERE id = ?", Long.class, scheduleId);
        var ids = new ArrayList<Long>();
        for (var i = 0; i < count; i++) {
            var venueSeatId = jdbc.queryForObject("INSERT INTO venue_seats (venue_id, section, row_no, seat_no, grade,"
                    + " created_at, updated_at) VALUES (?, 'A', 1, ?, 'VIP', now(), now()) RETURNING id",
                    Long.class, venueId, i + 1);
            ids.add(jdbc.queryForObject("INSERT INTO schedule_seats (schedule_id, venue_seat_id, section, grade, price,"
                    + " status, created_at, updated_at) VALUES (?, ?, 'A', 'VIP', ?, 'AVAILABLE', now(), now())"
                    + " RETURNING id", Long.class, scheduleId, venueSeatId, SEAT_PRICE));
        }
        return ids;
    }

    // 부하 시나리오용. 수천 석을 한 행씩 넣으면 준비가 측정보다 오래 걸려 generate_series 두 문장으로 만든다. ID 오름차순으로 돌려준다
    List<Long> createSeatsInBulk(Long scheduleId, int count) {
        var venueId = jdbc.queryForObject("SELECT venue_id FROM schedules WHERE id = ?", Long.class, scheduleId);
        jdbc.update("INSERT INTO venue_seats (venue_id, section, row_no, seat_no, grade, created_at, updated_at)"
                + " SELECT ?, 'A', g / 100 + 1, g % 100 + 1, 'VIP', now(), now() FROM generate_series(0, ? - 1) g",
                venueId, count);
        jdbc.update("INSERT INTO schedule_seats (schedule_id, venue_seat_id, section, grade, price, status, created_at,"
                + " updated_at) SELECT ?, id, 'A', 'VIP', ?, 'AVAILABLE', now(), now() FROM venue_seats"
                + " WHERE venue_id = ? ORDER BY id", scheduleId, SEAT_PRICE, venueId);
        return jdbc.queryForList("SELECT id FROM schedule_seats WHERE schedule_id = ? ORDER BY id", Long.class,
                scheduleId);
    }

    // Note: 기한은 DB now()로, 비교 기준은 앱 시계로 정한다. Docker DB 시계가 호스트보다 몇 초 앞설 수 있어 넉넉히 뺀다
    void passHoldExpiry(Long scheduleId) {
        jdbc.update("UPDATE reservations SET hold_expires_at = now() - interval '1 hour'"
                + " WHERE schedule_id = ? AND status = 'HELD'", scheduleId);
    }

    // DB 전체 누적값이다. 시나리오 전후 차이로 본다. 같은 DB를 쓰는 다른 테스트와 함께 돌리면 섞이므로 단독 실행한다
    long deadlocks() {
        return jdbc.queryForObject("SELECT deadlocks FROM pg_stat_database WHERE datname = current_database()",
                Long.class);
    }

    // 한 회차 좌석에 활성 예매 좌석이 2건 이상이면 중복 판매다. 부분 유니크 인덱스가 있어 0이 정상이다
    int doubleSoldSeats(Long scheduleId) {
        return jdbc.queryForObject("SELECT count(*) FROM (SELECT rs.schedule_seat_id FROM reservation_seats rs"
                + " JOIN schedule_seats ss ON ss.id = rs.schedule_seat_id WHERE ss.schedule_id = ?"
                + " AND rs.status IN ('HELD', 'CONFIRMED') GROUP BY rs.schedule_seat_id HAVING count(*) > 1) d",
                Integer.class, scheduleId);
    }

    int activeSeatsOfUser(long userId, Long scheduleId) {
        return jdbc.queryForObject("SELECT count(*) FROM reservation_seats rs JOIN reservations r"
                + " ON r.id = rs.reservation_id WHERE r.user_id = ? AND r.schedule_id = ?"
                + " AND rs.status IN ('HELD', 'CONFIRMED')", Integer.class, userId, scheduleId);
    }

    int heldCount(long userId, Long scheduleId) {
        return jdbc.queryForObject("SELECT COALESCE(SUM(held_count), 0) FROM user_schedule_quotas"
                + " WHERE user_id = ? AND schedule_id = ?", Integer.class, userId, scheduleId);
    }

    // 회차 좌석 상태와 활성 예매 좌석 원장이 어긋난 좌석 수(대사 D2와 같은 기준)
    int seatStatusMismatches(Long scheduleId) {
        return jdbc.queryForObject("SELECT count(*) FROM schedule_seats ss WHERE ss.schedule_id = ? AND"
                + " (ss.status = 'HELD') <> EXISTS (SELECT 1 FROM reservation_seats rs"
                + " WHERE rs.schedule_seat_id = ss.id AND rs.status = 'HELD')", Integer.class, scheduleId);
    }

    int reservationsOfRequest(UUID requestId) {
        return jdbc.queryForObject("SELECT count(*) FROM reservations WHERE request_id = ?", Integer.class,
                requestId);
    }

    void cleanUp() {
        for (var venueId : createdVenueIds) {
            var scheduleIds = "(SELECT id FROM schedules WHERE venue_id = ?)";
            var reservationIds = "(SELECT id FROM reservations WHERE schedule_id IN " + scheduleIds + ")";
            jdbc.update("DELETE FROM seat_hold_requests WHERE schedule_id IN " + scheduleIds, venueId);
            jdbc.update("DELETE FROM reservation_status_histories WHERE reservation_id IN " + reservationIds, venueId);
            jdbc.update("DELETE FROM reservation_seats WHERE reservation_id IN " + reservationIds, venueId);
            jdbc.update("DELETE FROM reservations WHERE schedule_id IN " + scheduleIds, venueId);
            jdbc.update("DELETE FROM user_schedule_quotas WHERE schedule_id IN " + scheduleIds, venueId);
            jdbc.update("DELETE FROM schedule_seats WHERE schedule_id IN " + scheduleIds, venueId);
            jdbc.update("DELETE FROM schedules WHERE venue_id = ?", venueId);
            jdbc.update("DELETE FROM venue_seats WHERE venue_id = ?", venueId);
            jdbc.update("DELETE FROM venues WHERE id = ?", venueId);
        }
        createdVenueIds.clear();
        jdbc.update("DELETE FROM concerts WHERE title = ? AND id NOT IN (SELECT concert_id FROM schedules)",
                CONCERT_TITLE);
    }
}
