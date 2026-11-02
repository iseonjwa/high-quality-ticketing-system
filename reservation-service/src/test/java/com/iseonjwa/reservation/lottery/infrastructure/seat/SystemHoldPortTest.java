package com.iseonjwa.reservation.lottery.infrastructure.seat;

import com.iseonjwa.common.response.BusinessException;
import com.iseonjwa.reservation.lottery.application.SystemHoldPort;
import com.iseonjwa.reservation.lottery.application.SystemHoldPort.LotteryHold;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

// 추첨 당첨 예매를 만드는 시스템 선점(ISE-88, D10). 배정 워커처럼 트랜잭션 안에서 포트를 부른다.
@SpringBootTest(properties = {"spring.config.import=", "spring.cloud.config.enabled=false", "eureka.client.enabled=false"})
@ActiveProfiles("test")
class SystemHoldPortTest {
    private static final String MARK = "시스템 선점 테스트";
    private static final long USER_ID = 6001L;

    @Autowired
    SystemHoldPort systemHoldPort;
    @Autowired
    TransactionTemplate tx;
    @Autowired
    JdbcTemplate jdbc;

    private Long scheduleId;
    private List<Long> lotterySeatIds;
    private Long generalSeatId;

    @BeforeEach
    void setUp() {
        Long concertId = jdbc.queryForObject("INSERT INTO concerts (title, version, created_at, updated_at)"
                + " VALUES (?, 0, now(), now()) RETURNING id", Long.class, MARK);
        Long venueId = jdbc.queryForObject("INSERT INTO venues (name, created_at, updated_at)"
                + " VALUES (?, now(), now()) RETURNING id", Long.class, MARK);
        scheduleId = jdbc.queryForObject("INSERT INTO schedules (concert_id, venue_id, starts_at, open_at, version,"
                + " created_at, updated_at) VALUES (?, ?, now() + interval '30 day', now() + interval '10 day', 0,"
                + " now(), now()) RETURNING id", Long.class, concertId, venueId);
        jdbc.update("INSERT INTO venue_seats (venue_id, section, row_no, seat_no, grade, created_at, updated_at)"
                + " SELECT ?, 'B', 1, g, 'S', now(), now() FROM generate_series(1, 4) g", venueId);
        jdbc.update("INSERT INTO schedule_seats (schedule_id, venue_seat_id, section, grade, price, status,"
                + " created_at, updated_at) SELECT ?, vs.id, vs.section, CASE WHEN vs.seat_no <= 3 THEN 'LOTTERY'"
                + " ELSE 'S' END, 121000, 'AVAILABLE', now(), now() FROM venue_seats vs WHERE vs.venue_id = ?"
                + " ORDER BY vs.id", scheduleId, venueId);
        lotterySeatIds = jdbc.queryForList("SELECT id FROM schedule_seats WHERE schedule_id = ? AND grade = 'LOTTERY'"
                + " ORDER BY id", Long.class, scheduleId);
        generalSeatId = jdbc.queryForObject("SELECT id FROM schedule_seats WHERE schedule_id = ? AND grade = 'S'",
                Long.class, scheduleId);
    }

    @AfterEach
    void cleanUp() {
        String reservationsOfSchedule = "(SELECT id FROM reservations WHERE schedule_id = ?)";
        jdbc.update("DELETE FROM reservation_status_histories WHERE reservation_id IN " + reservationsOfSchedule,
                scheduleId);
        jdbc.update("DELETE FROM reservation_seats WHERE reservation_id IN " + reservationsOfSchedule, scheduleId);
        jdbc.update("DELETE FROM reservations WHERE schedule_id = ?", scheduleId);
        jdbc.update("DELETE FROM user_schedule_quotas WHERE schedule_id = ?", scheduleId);
        jdbc.update("DELETE FROM schedule_seats WHERE schedule_id = ?", scheduleId);
        jdbc.update("DELETE FROM schedules WHERE concert_id IN (SELECT id FROM concerts WHERE title = ?)", MARK);
        jdbc.update("DELETE FROM concerts WHERE title = ?", MARK);
        jdbc.update("DELETE FROM venue_seats WHERE venue_id IN (SELECT id FROM venues WHERE name = ?)", MARK);
        jdbc.update("DELETE FROM venues WHERE name = ?", MARK);
    }

    @Test
    void 추첨석으로_LOTTERY_출처의_HELD_예매를_30분_선점으로_만든다() {
        var seatIds = List.of(lotterySeatIds.get(1), lotterySeatIds.get(0));
        Instant before = Instant.now();

        LotteryHold hold = tx.execute(status -> systemHoldPort.hold(USER_ID, scheduleId, seatIds));

        assertEquals(242_000, hold.totalAmount());
        var expiresIn = Duration.between(before, hold.holdExpiresAt());
        assertTrue(expiresIn.compareTo(Duration.ofMinutes(29)) > 0 && expiresIn.compareTo(Duration.ofMinutes(31)) < 0,
                "선점 만료까지 " + expiresIn);
        var reservation = jdbc.queryForMap("SELECT status, source, quantity, user_id FROM reservations WHERE id = ?",
                hold.reservationId());
        assertEquals("HELD", reservation.get("status"));
        assertEquals("LOTTERY", reservation.get("source"));
        assertEquals(2, ((Number) reservation.get("quantity")).intValue());
        assertEquals(List.of("HELD", "HELD"), jdbc.queryForList("SELECT status FROM schedule_seats WHERE id IN (?, ?)",
                String.class, seatIds.get(0), seatIds.get(1)));
        assertEquals(2, jdbc.queryForObject("SELECT held_count FROM user_schedule_quotas WHERE user_id = ?"
                + " AND schedule_id = ?", Integer.class, USER_ID, scheduleId));
        assertEquals("LOTTERY_ASSIGNED", jdbc.queryForObject("SELECT reason FROM reservation_status_histories"
                + " WHERE reservation_id = ?", String.class, hold.reservationId()));
    }

    // 일반 좌석이 섞이면 이미 잡힌 좌석처럼 거절하고, 먼저 바뀐 매수·좌석도 함께 롤백된다
    @Test
    void 추첨석이_아닌_좌석이_섞이면_SEAT_ALREADY_HELD이고_아무것도_남지_않는다() {
        var exception = assertThrows(BusinessException.class, () -> tx.execute(status ->
                systemHoldPort.hold(USER_ID, scheduleId, List.of(lotterySeatIds.get(0), generalSeatId))));

        assertEquals("SEAT_ALREADY_HELD", exception.getErrorCode().code());
        assertNothingHeld();
    }

    @Test
    void 이미_잡힌_추첨석은_SEAT_ALREADY_HELD다() {
        tx.execute(status -> systemHoldPort.hold(USER_ID, scheduleId, List.of(lotterySeatIds.get(0))));

        var exception = assertThrows(BusinessException.class, () -> tx.execute(status ->
                systemHoldPort.hold(USER_ID + 1, scheduleId, List.of(lotterySeatIds.get(0)))));

        assertEquals("SEAT_ALREADY_HELD", exception.getErrorCode().code());
    }

    // 당첨 예매도 일반 예매와 같은 회차당 매수 한도를 거친다(SA 12-3)
    @Test
    void 회차_매수_한도를_넘으면_PURCHASE_LIMIT_EXCEEDED이고_아무것도_남지_않는다() {
        jdbc.update("INSERT INTO user_schedule_quotas (user_id, schedule_id, held_count, created_at, updated_at)"
                + " VALUES (?, ?, 3, now(), now())", USER_ID, scheduleId);

        var exception = assertThrows(BusinessException.class, () -> tx.execute(status ->
                systemHoldPort.hold(USER_ID, scheduleId, List.of(lotterySeatIds.get(0), lotterySeatIds.get(1)))));

        assertEquals("PURCHASE_LIMIT_EXCEEDED", exception.getErrorCode().code());
        assertEquals(3, jdbc.queryForObject("SELECT held_count FROM user_schedule_quotas WHERE user_id = ?"
                + " AND schedule_id = ?", Integer.class, USER_ID, scheduleId));
        assertNothingHeld();
    }

    // 예매만 커밋되고 응모 연결이 빠지는 일을 막으려고 배정 워커의 트랜잭션 밖에서는 부를 수 없게 한다
    @Test
    void 트랜잭션_밖에서_부르면_거절한다() {
        assertThrows(IllegalTransactionStateException.class,
                () -> systemHoldPort.hold(USER_ID, scheduleId, List.of(lotterySeatIds.get(0))));
    }

    private void assertNothingHeld() {
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM reservations WHERE schedule_id = ?", Integer.class,
                scheduleId));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM schedule_seats WHERE schedule_id = ?"
                + " AND status = 'HELD'", Integer.class, scheduleId));
    }
}
