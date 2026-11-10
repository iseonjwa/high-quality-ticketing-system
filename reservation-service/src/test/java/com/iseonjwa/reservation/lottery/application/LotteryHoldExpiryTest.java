package com.iseonjwa.reservation.lottery.application;

import com.iseonjwa.reservation.seathold.application.SeatHoldExpiryService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.sql.Timestamp;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// 선점 만료와 추첨 처리기 연결(ISE-149, D12). 배정 워커로 당첨 예매를 만든 뒤 기한을 SQL로 당겨 만료 서비스를 직접 부른다.
@SpringBootTest(properties = {"spring.config.import=", "spring.cloud.config.enabled=false", "eureka.client.enabled=false"})
@ActiveProfiles("test")
class LotteryHoldExpiryTest {
    private static final String MARK = "추첨 만료 테스트";

    @Autowired
    LotteryAssignmentService assignmentService;
    @Autowired
    SeatHoldExpiryService expiryService;
    @Autowired
    JdbcTemplate jdbc;

    private Long venueId;
    private Long scheduleId;
    private Long lotteryId;

    @BeforeEach
    void setUp() {
        Long concertId = jdbc.queryForObject("INSERT INTO concerts (title, version, created_at, updated_at)"
                + " VALUES (?, 0, now(), now()) RETURNING id", Long.class, MARK);
        venueId = jdbc.queryForObject("INSERT INTO venues (name, created_at, updated_at)"
                + " VALUES (?, now(), now()) RETURNING id", Long.class, MARK);
        scheduleId = jdbc.queryForObject("INSERT INTO schedules (concert_id, venue_id, starts_at, open_at, version,"
                + " created_at, updated_at) VALUES (?, ?, now() + interval '30 day', now() + interval '10 day', 0,"
                + " now(), now()) RETURNING id", Long.class, concertId, venueId);
        jdbc.update("INSERT INTO venue_seats (venue_id, section, row_no, seat_no, grade, created_at, updated_at)"
                + " SELECT ?, 'B', 1, n, 'S', now(), now() FROM generate_series(1, 2) n", venueId);
        jdbc.update("INSERT INTO schedule_seats (schedule_id, venue_seat_id, section, grade, price, status,"
                + " created_at, updated_at) SELECT ?, vs.id, vs.section, 'LOTTERY', 100000, 'AVAILABLE', now(), now()"
                + " FROM venue_seats vs WHERE vs.venue_id = ? ORDER BY vs.seat_no", scheduleId, venueId);
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM outbox_events WHERE event_type = 'BillingPaymentRequested'"
                + " AND (payload -> 'payload' ->> 'lotteryEntryId')::bigint IN"
                + " (SELECT id FROM lottery_entries WHERE lottery_id = ?)", lotteryId);
        jdbc.update("DELETE FROM lottery_entries WHERE lottery_id = ?", lotteryId);
        jdbc.update("DELETE FROM lotteries WHERE schedule_id = ?", scheduleId);
        String reservationsOfSchedule = "(SELECT id FROM reservations WHERE schedule_id = ?)";
        jdbc.update("DELETE FROM reservation_status_histories WHERE reservation_id IN " + reservationsOfSchedule,
                scheduleId);
        jdbc.update("DELETE FROM reservation_seats WHERE reservation_id IN " + reservationsOfSchedule, scheduleId);
        jdbc.update("DELETE FROM reservations WHERE schedule_id = ?", scheduleId);
        jdbc.update("DELETE FROM user_schedule_quotas WHERE schedule_id = ?", scheduleId);
        jdbc.update("DELETE FROM schedule_seats WHERE schedule_id = ?", scheduleId);
        jdbc.update("DELETE FROM schedules WHERE id = ?", scheduleId);
        jdbc.update("DELETE FROM concerts WHERE title = ?", MARK);
        jdbc.update("DELETE FROM venue_seats WHERE venue_id = ?", venueId);
        jdbc.update("DELETE FROM venues WHERE id = ?", venueId);
    }

    // 완료 기준: 결제 결과를 받지 못한 첫 만료는 만료 대신 같은 예매 선점을 30분 연장한다(D12)
    @Test
    void 결과_없이_기한이_지나면_같은_예매를_한_번_연장한다() {
        lotteryId = insertDrawnLottery("now() - interval '1 minute'");
        Long entryId = insertWinner(8101L);
        Long reservationId = assign(entryId);
        moveHold(reservationId, Duration.ofMinutes(31));
        Timestamp expired = holdExpiresAt(reservationId);

        assertFalse(expiryService.expire(reservationId));

        assertEquals("HELD", reservation(reservationId).get("status"));
        assertEquals(expired.toInstant().plus(Duration.ofMinutes(30)), holdExpiresAt(reservationId).toInstant());
        assertEquals("WON", entry(entryId).get("status"));
        assertEquals(reservationId, ((Number) entry(entryId).get("reservation_id")).longValue());
    }

    // 완료 기준: 연장한 뒤에도 결과가 없으면 만료하고 응모는 FAILED(HOLD_EXPIRED), 좌석·매수는 일반 만료처럼 돌려준다
    @Test
    void 연장한_선점도_기한이_지나면_만료하고_응모를_FAILED로_바꾼다() {
        lotteryId = insertDrawnLottery("now() - interval '1 minute'");
        Long entryId = insertWinner(8201L);
        Long reservationId = assign(entryId);
        moveHold(reservationId, Duration.ofMinutes(31));
        assertFalse(expiryService.expire(reservationId));
        moveHold(reservationId, Duration.ofMinutes(31));

        assertTrue(expiryService.expire(reservationId));

        assertEquals("EXPIRED", reservation(reservationId).get("status"));
        assertEquals("FAILED", entry(entryId).get("status"));
        assertEquals("HOLD_EXPIRED", entry(entryId).get("fail_reason"));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM schedule_seats WHERE schedule_id = ?"
                + " AND status = 'HELD'", Integer.class, scheduleId));
    }

    // 완료 기준·변이 테스트: 상한(승계 마감 + 30분 = 추첨 후 60분)이 지났으면 연장하지 않고 바로 만료한다
    @Test
    void 연장_상한이_지났으면_연장하지_않고_만료한다() {
        lotteryId = insertDrawnLottery("now() - interval '61 minute'");
        Long entryId = insertWinner(8301L);
        Long reservationId = assign(entryId);
        moveHold(reservationId, Duration.ofMinutes(31));

        assertTrue(expiryService.expire(reservationId));

        assertEquals("EXPIRED", reservation(reservationId).get("status"));
        assertEquals("FAILED", entry(entryId).get("status"));
        assertEquals("HOLD_EXPIRED", entry(entryId).get("fail_reason"));
    }

    // 연장해도 상한(추첨 후 60분)을 넘기지 않는다
    @Test
    void 연장은_상한까지만_늘린다() {
        lotteryId = insertDrawnLottery("now() - interval '50 minute'");
        Long entryId = insertWinner(8401L);
        Long reservationId = assign(entryId);
        moveHold(reservationId, Duration.ofMinutes(31));

        assertFalse(expiryService.expire(reservationId));

        Timestamp limit = jdbc.queryForObject("SELECT draw_at + interval '60 minute' FROM lotteries WHERE id = ?",
                Timestamp.class, lotteryId);
        assertEquals(limit, holdExpiresAt(reservationId));
        assertEquals("WON", entry(entryId).get("status"));
    }

    // 완료 기준·변이 테스트: 상한에 잘린 연장은 "기한 < 생성 + 31분"이라 연장 전처럼 보이므로 상한 검사만이 재연장을 막는다
    @Test
    void 상한에_잘린_연장_뒤_상한이_지나면_다시_연장하지_않고_만료한다() {
        lotteryId = insertDrawnLottery("now() - interval '59 minutes 40 seconds'");
        Long entryId = insertWinner(8501L);
        Long reservationId = assign(entryId);
        jdbc.update("UPDATE reservations SET created_at = now() - interval '30 minutes',"
                + " hold_expires_at = now() - interval '10 seconds' WHERE id = ?", reservationId);

        assertFalse(expiryService.expire(reservationId));

        Timestamp limit = jdbc.queryForObject("SELECT draw_at + interval '60 minute' FROM lotteries WHERE id = ?",
                Timestamp.class, lotteryId);
        assertEquals(limit, holdExpiresAt(reservationId));
        assertTrue(jdbc.queryForObject("SELECT hold_expires_at < created_at + interval '31 minutes'"
                + " FROM reservations WHERE id = ?", Boolean.class, reservationId));

        moveHold(reservationId, Duration.ofMinutes(1));
        jdbc.update("UPDATE lotteries SET draw_at = draw_at - interval '1 minute' WHERE id = ?", lotteryId);

        assertTrue(expiryService.expire(reservationId));

        assertEquals("EXPIRED", reservation(reservationId).get("status"));
        assertEquals("FAILED", entry(entryId).get("status"));
        assertEquals("HOLD_EXPIRED", entry(entryId).get("fail_reason"));
    }

    private Long assign(Long entryId) {
        assertEquals(entryId, assignmentService.assignNext().orElseThrow());
        return ((Number) entry(entryId).get("reservation_id")).longValue();
    }

    // 생성·기한을 같은 만큼 과거로 옮긴다. 연장 여부는 "기한이 생성 + 31분 안인가"로 보므로 둘을 함께 옮겨야 실제와 같다
    private void moveHold(Long reservationId, Duration back) {
        jdbc.update("UPDATE reservations SET created_at = created_at - make_interval(secs => ?),"
                + " hold_expires_at = hold_expires_at - make_interval(secs => ?) WHERE id = ?",
                back.toSeconds(), back.toSeconds(), reservationId);
    }

    private Long insertDrawnLottery(String drawAtSql) {
        return jdbc.queryForObject("INSERT INTO lotteries (schedule_id, source_grade, seat_count, waitlist_count,"
                + " max_per_entry, entry_start_at, entry_end_at, draw_at, seed, status, created_at, updated_at)"
                + " VALUES (?, 'S', 2, 0, 2, now() - interval '3 hour', now() - interval '2 hour', " + drawAtSql
                + ", ?, 'DRAWN', now(), now()) RETURNING id", Long.class, scheduleId, new byte[32]);
    }

    private Long insertWinner(long userId) {
        return jdbc.queryForObject("INSERT INTO lottery_entries (lottery_id, request_id, user_id, quantity, status,"
                + " draw_rank, created_at, updated_at) VALUES (?, gen_random_uuid(), ?, 1, 'WON', 1, now(), now())"
                + " RETURNING id", Long.class, lotteryId, userId);
    }

    private Map<String, Object> entry(Long entryId) {
        return jdbc.queryForMap("SELECT status, reservation_id, fail_reason FROM lottery_entries WHERE id = ?",
                entryId);
    }

    private Map<String, Object> reservation(Long reservationId) {
        return jdbc.queryForMap("SELECT status FROM reservations WHERE id = ?", reservationId);
    }

    private Timestamp holdExpiresAt(Long reservationId) {
        return jdbc.queryForObject("SELECT hold_expires_at FROM reservations WHERE id = ?", Timestamp.class,
                reservationId);
    }
}
