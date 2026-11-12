package com.iseonjwa.reservation.seathold.application;

import com.iseonjwa.reservation.seathold.presentation.scheduling.SeatHoldExpiryScheduler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// 선점 만료 스케줄러를 실제 PostgreSQL로 확인한다(ISE-13, E7). 테스트 프로필은 백그라운드 주기를 끄고 메서드를 직접 부른다
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.config.import=", "spring.cloud.config.enabled=false", "eureka.client.enabled=false"})
@ActiveProfiles("test")
class SeatHoldExpiryTest {
    private static final long USER = 42L;
    private static final long OTHER_USER = 43L;
    private static final long SEAT_PRICE = 165000L;
    private static final int BATCH_SIZE = 100;

    private final List<Long> createdVenueIds = new ArrayList<>();

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    SyncSeatHoldService seatHold;
    @Autowired
    SeatHoldExpiryService expiry;

    private SeatHoldExpiryScheduler scheduler;
    private Long scheduleId;
    private List<Long> seatIds;

    @BeforeEach
    void setUp() {
        scheduler = new SeatHoldExpiryScheduler(expiry, BATCH_SIZE);
        scheduleId = createSchedule();
        seatIds = createSeats(scheduleId, 3);
    }

    @AfterEach
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
        jdbc.update("DELETE FROM concerts WHERE title = 'ISE-13 만료 테스트' AND id NOT IN (SELECT concert_id FROM schedules)");
    }

    @Test
    void 기한이_지난_HELD_예매는_EXPIRED가_되고_좌석과_매수를_돌려준다() {
        var reservationId = hold(USER, List.of(seatIds.get(0), seatIds.get(1)));
        passHoldExpiry(reservationId);

        scheduler.expireDueHolds();

        assertEquals("EXPIRED", reservationStatus(reservationId));
        assertNull(jdbc.queryForObject("SELECT updated_by FROM reservations WHERE id = ?", Long.class, reservationId));
        assertEquals(List.of("RELEASED", "RELEASED"), reservationSeatStatuses(reservationId));
        assertEquals(List.of("AVAILABLE", "AVAILABLE", "AVAILABLE"), seatStatuses());
        assertEquals(0, heldCount(USER));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM reservation_status_histories"
                + " WHERE reservation_id = ? AND from_status = 'HELD' AND to_status = 'EXPIRED'"
                + " AND reason = 'HOLD_EXPIRED'", Integer.class, reservationId));
    }

    @Test
    void 만료로_풀린_좌석은_다른_사용자가_다시_선점할_수_있다() {
        var expiredId = hold(USER, List.of(seatIds.get(0)));
        passHoldExpiry(expiredId);
        scheduler.expireDueHolds();

        var newId = hold(OTHER_USER, List.of(seatIds.get(0)));

        assertEquals("HELD", reservationStatus(newId));
        assertEquals(List.of("HELD", "AVAILABLE", "AVAILABLE"), seatStatuses());
        assertEquals(1, heldCount(OTHER_USER));
    }

    @Test
    void 기한이_남은_HELD_예매는_그대로_둔다() {
        var reservationId = hold(USER, List.of(seatIds.get(0)));

        scheduler.expireDueHolds();

        assertEquals("HELD", reservationStatus(reservationId));
        assertEquals(List.of("HELD"), reservationSeatStatuses(reservationId));
        assertEquals(List.of("HELD", "AVAILABLE", "AVAILABLE"), seatStatuses());
        assertEquals(1, heldCount(USER));
    }

    @Test
    void 이미_CONFIRMED나_RELEASED로_바뀐_예매는_기한이_지나도_만료하지_않는다() {
        var confirmedId = hold(USER, List.of(seatIds.get(0)));
        var releasedId = hold(OTHER_USER, List.of(seatIds.get(1)));
        // 결제 결과 소비자가 먼저 상태를 바꾼 경우를 흉내 낸다(확정은 SOLD, 거절 해제는 좌석 반환)
        changeStatus(confirmedId, "CONFIRMED", "CONFIRMED", "SOLD");
        changeStatus(releasedId, "RELEASED", "RELEASED", "AVAILABLE");
        passHoldExpiry(confirmedId);
        passHoldExpiry(releasedId);

        scheduler.expireDueHolds();

        assertEquals("CONFIRMED", reservationStatus(confirmedId));
        assertEquals("RELEASED", reservationStatus(releasedId));
        assertEquals(List.of("SOLD", "AVAILABLE", "AVAILABLE"), seatStatuses());
        assertEquals(1, heldCount(USER));
        assertEquals(1, heldCount(OTHER_USER));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM reservation_status_histories"
                + " WHERE reservation_id IN (?, ?) AND to_status = 'EXPIRED'", Integer.class, confirmedId, releasedId));
    }

    @Test
    void 같은_예매를_두_번_만료해도_좌석과_매수는_한_번만_돌려준다() {
        var reservationId = hold(USER, List.of(seatIds.get(0), seatIds.get(1)));
        passHoldExpiry(reservationId);

        assertTrue(expiry.expire(reservationId));
        assertFalse(expiry.expire(reservationId));

        assertEquals(0, heldCount(USER));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM reservation_status_histories"
                + " WHERE reservation_id = ? AND to_status = 'EXPIRED'", Integer.class, reservationId));
    }

    private Long hold(long userId, List<Long> seats) {
        return ((SyncHoldOutcome.Held) seatHold.hold(new HoldSeatsCommand(userId, UUID.randomUUID(), scheduleId, seats)))
                .result().reservationId();
    }

    // 5분을 기다리지 않고 선점 기한을 과거로 옮긴다.
    // Note: 기한은 DB now()로, 비교 기준은 앱 시계로 정한다. Docker DB 시계가 호스트보다 몇 초 앞설 수 있어 넉넉히 뺀다
    private void passHoldExpiry(Long reservationId) {
        jdbc.update("UPDATE reservations SET hold_expires_at = now() - interval '1 hour' WHERE id = ?",
                reservationId);
    }

    private void changeStatus(Long reservationId, String reservationStatus, String reservationSeatStatus,
            String scheduleSeatStatus) {
        jdbc.update("UPDATE reservations SET status = ? WHERE id = ?", reservationStatus, reservationId);
        jdbc.update("UPDATE reservation_seats SET status = ? WHERE reservation_id = ?", reservationSeatStatus,
                reservationId);
        jdbc.update("UPDATE schedule_seats SET status = ? WHERE id IN"
                + " (SELECT schedule_seat_id FROM reservation_seats WHERE reservation_id = ?)",
                scheduleSeatStatus, reservationId);
    }

    private Long createSchedule() {
        var venueId = jdbc.queryForObject("INSERT INTO venues (name, created_at, updated_at)"
                + " VALUES ('ISE-13 테스트 공연장', now(), now()) RETURNING id", Long.class);
        createdVenueIds.add(venueId);
        var concertId = jdbc.queryForObject("INSERT INTO concerts (title, version, created_at, updated_at)"
                + " VALUES ('ISE-13 만료 테스트', 0, now(), now()) RETURNING id", Long.class);
        return jdbc.queryForObject("INSERT INTO schedules (concert_id, venue_id, starts_at, open_at, version,"
                + " created_at, updated_at) VALUES (?, ?, now() + interval '1 day', now() - interval '1 minute',"
                + " 0, now(), now()) RETURNING id", Long.class, concertId, venueId);
    }

    private List<Long> createSeats(Long scheduleId, int count) {
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

    private String reservationStatus(Long reservationId) {
        return jdbc.queryForObject("SELECT status FROM reservations WHERE id = ?", String.class, reservationId);
    }

    private List<String> reservationSeatStatuses(Long reservationId) {
        return jdbc.queryForList("SELECT status FROM reservation_seats WHERE reservation_id = ? ORDER BY id",
                String.class, reservationId);
    }

    private List<String> seatStatuses() {
        var statuses = new ArrayList<String>();
        for (var seatId : seatIds) {
            statuses.add(jdbc.queryForObject("SELECT status FROM schedule_seats WHERE id = ?", String.class, seatId));
        }
        return statuses;
    }

    private int heldCount(long userId) {
        return jdbc.queryForObject("SELECT COALESCE(SUM(held_count), 0) FROM user_schedule_quotas"
                + " WHERE user_id = ? AND schedule_id = ?", Integer.class, userId, scheduleId);
    }
}
