package com.iseonjwa.reservation.lottery.scenario;

import com.iseonjwa.reservation.concert.domain.entity.SeatGrade;
import com.iseonjwa.reservation.lottery.application.CreateLotteryCommand;
import com.iseonjwa.reservation.lottery.application.LotteryAdminService;
import com.iseonjwa.reservation.lottery.application.LotteryAssignmentService;
import com.iseonjwa.reservation.lottery.application.LotteryAssignmentService.AssignmentRejected;
import com.iseonjwa.reservation.lottery.application.LotteryDrawService;
import com.iseonjwa.reservation.lottery.application.LotteryEntryService;
import com.iseonjwa.reservation.lottery.application.LotteryFinishService;
import com.iseonjwa.reservation.lottery.application.LotteryPaymentResultHandler;
import com.iseonjwa.reservation.lottery.application.LotterySuccessionService;
import com.iseonjwa.reservation.lottery.domain.Lottery.LotterySettings;
import com.iseonjwa.reservation.seathold.application.SeatHoldExpiryService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 추첨 서비스 내부 시나리오(ISE-151). 설정부터 종결까지 서비스를 차례로 불러 기능이 이어지는지 확인한다.
 * 스케줄러는 끄고, 시각은 SQL로 옮긴다. 결제 결과는 추첨 처리기를 직접 불러 대신하고, 결제 결과 소비자(ISE-73)가 할
 * 거절 예매의 좌석 반환은 {@link #releaseLikeResultConsumer}로 흉내 낸다. 서비스 사이 흐름은 ISE-143 E2E에서 본다.
 */
@SpringBootTest(properties = {"spring.config.import=", "spring.cloud.config.enabled=false", "eureka.client.enabled=false"})
@ActiveProfiles("test")
class LotteryScenarioTest {
    private static final String MARK = "추첨 시나리오 테스트";
    private static final long FIRST_USER = 9_500_000;

    @Autowired
    LotteryAdminService adminService;
    @Autowired
    LotteryEntryService entryService;
    @Autowired
    LotteryDrawService drawService;
    @Autowired
    LotteryAssignmentService assignmentService;
    @Autowired
    LotteryPaymentResultHandler resultHandler;
    @Autowired
    LotterySuccessionService successionService;
    @Autowired
    LotteryFinishService finishService;
    @Autowired
    SeatHoldExpiryService expiryService;
    @Autowired
    JdbcTemplate jdbc;

    private Long venueId;
    private Long scheduleId;
    private Long lotteryId;
    private long nextUser = FIRST_USER;

    @BeforeEach
    void setUp() {
        Long concertId = jdbc.queryForObject("INSERT INTO concerts (title, version, created_at, updated_at)"
                + " VALUES (?, 0, now(), now()) RETURNING id", Long.class, MARK);
        venueId = jdbc.queryForObject("INSERT INTO venues (name, created_at, updated_at)"
                + " VALUES (?, now(), now()) RETURNING id", Long.class, MARK);
        scheduleId = jdbc.queryForObject("INSERT INTO schedules (concert_id, venue_id, starts_at, open_at, version,"
                + " created_at, updated_at) VALUES (?, ?, now() + interval '30 day', now() + interval '10 day', 0,"
                + " now(), now()) RETURNING id", Long.class, concertId, venueId);
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM outbox_events WHERE event_type = 'BillingPaymentRequested'"
                + " AND message_key IN (SELECT id::text FROM reservations WHERE schedule_id = ?)", scheduleId);
        jdbc.update("DELETE FROM lottery_entries WHERE lottery_id IN (SELECT id FROM lotteries WHERE schedule_id = ?)",
                scheduleId);
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

    // S1 정상: 당첨자 전원이 결제하고 예비자는 승계 마감에 LOST, 종결 때 추첨석이 원래 등급으로 돌아간다
    @Test
    void S1_설정부터_종결까지_정상_흐름() {
        setUpLottery(4, 2, 4);
        var users = enterAll(6, 1);
        closeAndDraw();

        assertEquals(4, countEntries("WON"));
        assertEquals(2, countEntries("WAITLISTED"));
        assertEquals(4, assignAll().size());
        assertEquals(4, billingRequests(), "당첨 예매 1건당 자동결제 요청 1건");
        reservationsOfWinners().forEach(resultHandler::onPaid);
        assertEquals(4, countEntries("PAID"));

        passSuccessionDeadline();
        assertEquals(2, countEntries("LOST"));
        assertEquals(Optional.of(4), finishService.finish(lotteryId));

        assertEquals("DONE", lotteryStatus());
        assertEquals(0, seats("LOTTERY"));
        assertEquals(6, users.size());
    }

    // S2 결제 거절 → 승계 → 재배정 → 결제 성공 → 종결
    @Test
    void S2_결제가_거절되면_예비자가_승계해_자리를_채운다() {
        setUpLottery(2, 1, 2);
        enterAll(3, 1);
        closeAndDraw();
        assignAll();
        var reservations = reservationsOfWinners();

        resultHandler.onFailed(reservations.getFirst(), "CARD_DECLINED");
        releaseLikeResultConsumer(reservations.getFirst());
        assertEquals(1, successionService.succeed(lotteryId));
        assertEquals(1, assignAll().size());

        reservationsOfWinners().forEach(resultHandler::onPaid);
        assertEquals(2, countEntries("PAID"));
        assertEquals(1, countEntries("FAILED"));
        assertEquals(Optional.of(2), finishService.finish(lotteryId));
    }

    // S3 NOT_SENT는 같은 당첨자에게 새 예매로 1회 재시도한다(D12)
    @Test
    void S3_NOT_SENT면_같은_당첨자가_새_예매로_다시_결제한다() {
        setUpLottery(1, 0, 1);
        enterAll(1, 1);
        closeAndDraw();
        assignAll();
        Long first = reservationsOfWinners().getFirst();

        resultHandler.onFailed(first, "NOT_SENT");
        releaseLikeResultConsumer(first);
        assignAll();
        Long second = reservationsOfWinners().getFirst();
        resultHandler.onPaid(second);

        assertFalse(first.equals(second));
        assertEquals(2, billingRequests());
        assertEquals(1, countEntries("PAID"));
        assertEquals(2, jdbc.queryForObject("SELECT payment_attempts FROM lottery_entries WHERE lottery_id = ?",
                Integer.class, lotteryId));
    }

    // S4 결과를 받지 못한 만료는 1회 연장하고, 그 뒤 만료면 FAILED → 승계(D12·ISE-149)
    @Test
    void S4_결과_없이_만료되면_한_번_연장한_뒤_FAILED가_되고_승계한다() {
        setUpLottery(1, 1, 1);
        enterAll(2, 1);
        closeAndDraw();
        assignAll();
        Long reservation = reservationsOfWinners().getFirst();

        moveHoldBack(reservation);
        assertFalse(expiryService.expire(reservation), "첫 만료는 연장");
        moveHoldBack(reservation);
        assertTrue(expiryService.expire(reservation));

        assertEquals(1, countEntries("FAILED"));
        assertEquals(1, successionService.succeed(lotteryId));
        assertEquals(1, assignAll().size());
    }

    // S5 빈 자리가 남으면 종결 때 원래 등급으로 돌아가 일반 판매에 나온다(D9)
    @Test
    void S5_남은_추첨석은_종결_뒤_S석으로_일반_판매에_돌아간다() {
        setUpLottery(3, 0, 3);
        enterAll(2, 1);
        closeAndDraw();
        assignAll();
        reservationsOfWinners().forEach(resultHandler::onPaid);

        assertEquals(Optional.of(3), finishService.finish(lotteryId));

        assertEquals(0, seats("LOTTERY"));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM schedule_seats WHERE schedule_id = ?"
                + " AND grade = 'S' AND status = 'AVAILABLE'", Integer.class, scheduleId));
    }

    // S6 응모가 없으면 추첨 직후 종결해 추첨석을 모두 돌려준다
    @Test
    void S6_응모가_없으면_바로_종결하고_추첨석을_모두_돌려준다() {
        setUpLottery(2, 0, 2);
        closeAndDraw();

        assertTrue(finishService.findFinishDueIds().contains(lotteryId));
        assertEquals(Optional.of(2), finishService.finish(lotteryId));
        assertEquals(2, seats("S"));
    }

    // S7 2매 응모: 3석에 2매 둘이면 둘 다 당첨될 수 없고, 2매 당첨자는 옆자리를 받는다(D9)
    @Test
    void S7_2매_응모는_옆자리를_받고_남은_1석에서는_건너뛴다() {
        setUpLottery(3, 0, 3);
        enterAll(2, 2);
        enterAll(1, 1);
        closeAndDraw();
        assignAll();

        assertTrue(jdbc.queryForObject("SELECT coalesce(sum(quantity), 0) FROM lottery_entries WHERE lottery_id = ?"
                + " AND status = 'WON'", Integer.class, lotteryId) <= 3);
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM lottery_entries WHERE lottery_id = ?"
                + " AND status = 'WON' AND quantity = 2", Integer.class, lotteryId));
        var pair = jdbc.queryForList("SELECT vs.seat_no FROM lottery_entries e"
                + " JOIN reservation_seats rs ON rs.reservation_id = e.reservation_id"
                + " JOIN schedule_seats ss ON ss.id = rs.schedule_seat_id JOIN venue_seats vs ON vs.id = ss.venue_seat_id"
                + " WHERE e.lottery_id = ? AND e.quantity = 2 AND e.status = 'WON' ORDER BY vs.seat_no",
                Integer.class, lotteryId);
        assertEquals(2, pair.size());
        assertEquals(1, pair.get(1) - pair.get(0), "2매 당첨자는 옆자리");
    }

    // S8 취소한 응모는 추첨 대상에서 빠진다(D5)
    @Test
    void S8_취소한_응모는_추첨에서_빠진다() {
        setUpLottery(2, 0, 2);
        var users = enterAll(3, 1);
        entryService.cancel(users.getFirst(), lotteryId);
        closeAndDraw();

        assertEquals("CANCELED", jdbc.queryForObject("SELECT status FROM lottery_entries WHERE lottery_id = ?"
                + " AND user_id = ?", String.class, lotteryId, users.getFirst()));
        assertEquals(2, countEntries("WON"));
        assertEquals(0, countEntries("LOST"));
    }

    // S9 스케줄러가 같은 단계를 두 번 돌려도 결과가 같다(조건부 UPDATE·SKIP LOCKED·멱등 처리기)
    @Test
    void S9_각_단계를_두_번씩_불러도_결과가_같다() {
        setUpLottery(2, 1, 2);
        enterAll(3, 1);
        closeAndDraw();
        assertFalse(drawService.draw(lotteryId), "이미 추첨한 추첨은 다시 하지 않는다");
        assignAll();
        assignAll();
        var reservations = reservationsOfWinners();
        reservations.forEach(resultHandler::onPaid);
        reservations.forEach(resultHandler::onPaid);
        assertEquals(0, successionService.succeed(lotteryId));

        passSuccessionDeadline();
        successionService.closeExpiredWaitlists();
        assertTrue(finishService.finish(lotteryId).isPresent());
        assertEquals(Optional.empty(), finishService.finish(lotteryId));

        assertEquals(2, billingRequests());
        assertEquals(2, countEntries("PAID"));
        assertEquals(1, countEntries("LOST"));
    }

    // S10 같은 시드와 같은 응모면 다시 추첨해도 당첨자와 순위가 같다(D7)
    @Test
    void S10_같은_시드로_다시_추첨하면_같은_결과다() {
        setUpLottery(2, 1, 2);
        enterAll(5, 1);
        closeAndDraw();
        var first = ranks();

        jdbc.update("UPDATE lottery_entries SET status = 'ENTERED', draw_rank = NULL WHERE lottery_id = ?", lotteryId);
        jdbc.update("UPDATE lotteries SET status = 'CLOSED' WHERE id = ?", lotteryId);
        assertTrue(drawService.draw(lotteryId));

        assertEquals(first, ranks());
    }

    private void setUpLottery(int seatCount, int waitlistCount, int sSeats) {
        jdbc.update("INSERT INTO venue_seats (venue_id, section, row_no, seat_no, grade, created_at, updated_at)"
                + " SELECT ?, 'B', 1, n, 'S', now(), now() FROM generate_series(1, ?) n", venueId, sSeats);
        jdbc.update("INSERT INTO schedule_seats (schedule_id, venue_seat_id, section, grade, price, status,"
                + " created_at, updated_at) SELECT ?, vs.id, vs.section, 'S', 100000, 'AVAILABLE', now(), now()"
                + " FROM venue_seats vs WHERE vs.venue_id = ? ORDER BY vs.seat_no", scheduleId, venueId);
        var now = Instant.now();
        var settings = new LotterySettings(SeatGrade.S, seatCount, waitlistCount, 2, now.plus(Duration.ofHours(1)),
                now.plus(Duration.ofHours(2)), now.plus(Duration.ofHours(3)));
        lotteryId = adminService.create(new CreateLotteryCommand(scheduleId, settings)).lotteryId();
        assertEquals(seatCount, seats("LOTTERY"), "설정하면 S석이 추첨석이 된다");
        jdbc.update("UPDATE lotteries SET entry_start_at = now() - interval '1 hour',"
                + " entry_end_at = now() + interval '1 hour', draw_at = now() + interval '2 hour' WHERE id = ?", lotteryId);
    }

    private List<Long> enterAll(int count, int quantity) {
        var users = new ArrayList<Long>();
        for (int i = 0; i < count; i++) {
            long user = nextUser++;
            entryService.enter(user, UUID.randomUUID(), lotteryId, quantity);
            users.add(user);
        }
        return users;
    }

    private void closeAndDraw() {
        jdbc.update("UPDATE lotteries SET entry_start_at = now() - interval '2 hour',"
                + " entry_end_at = now() - interval '2 minute', draw_at = now() - interval '1 minute' WHERE id = ?",
                lotteryId);
        assertTrue(drawService.closeDueLotteries() >= 1);
        assertTrue(drawService.draw(lotteryId));
    }

    // 배정 스케줄러 한 주기와 같다. 거절은 새 트랜잭션에서 FAILED로 남긴다
    private List<Long> assignAll() {
        var assigned = new ArrayList<Long>();
        while (true) {
            try {
                var next = assignmentService.assignNext();
                if (next.isEmpty()) return assigned;
                assigned.add(next.get());
            } catch (AssignmentRejected rejected) {
                assignmentService.markRejected(rejected.entryId(), rejected.reason());
            }
        }
    }

    private void passSuccessionDeadline() {
        jdbc.update("UPDATE lotteries SET entry_start_at = now() - interval '3 hour',"
                + " entry_end_at = now() - interval '32 minute', draw_at = now() - interval '31 minute' WHERE id = ?",
                lotteryId);
        successionService.closeExpiredWaitlists();
    }

    // 결제 결과 소비자(ISE-73)가 거절·재시도 예매에 할 일: 예매 RELEASED, 좌석 반환. 추첨 처리기는 이 일을 하지 않는다
    private void releaseLikeResultConsumer(Long reservationId) {
        jdbc.update("UPDATE schedule_seats SET status = 'AVAILABLE' WHERE id IN"
                + " (SELECT schedule_seat_id FROM reservation_seats WHERE reservation_id = ?)", reservationId);
        jdbc.update("UPDATE reservation_seats SET status = 'RELEASED' WHERE reservation_id = ?", reservationId);
        jdbc.update("UPDATE reservations SET status = 'RELEASED' WHERE id = ?", reservationId);
    }

    // 생성·기한을 31분 과거로 옮긴다. 연장 여부는 "기한이 생성 + 31분 안인가"로 보므로 둘을 함께 옮긴다
    private void moveHoldBack(Long reservationId) {
        jdbc.update("UPDATE reservations SET created_at = created_at - interval '31 minute',"
                + " hold_expires_at = hold_expires_at - interval '31 minute' WHERE id = ?", reservationId);
    }

    private List<Long> reservationsOfWinners() {
        return jdbc.queryForList("SELECT reservation_id FROM lottery_entries WHERE lottery_id = ? AND status = 'WON'"
                + " AND reservation_id IS NOT NULL ORDER BY draw_rank", Long.class, lotteryId);
    }

    private int countEntries(String status) {
        return jdbc.queryForObject("SELECT count(*) FROM lottery_entries WHERE lottery_id = ? AND status = ?",
                Integer.class, lotteryId, status);
    }

    private int seats(String grade) {
        return jdbc.queryForObject("SELECT count(*) FROM schedule_seats WHERE schedule_id = ? AND grade = ?",
                Integer.class, scheduleId, grade);
    }

    private int billingRequests() {
        return jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE event_type = 'BillingPaymentRequested'"
                + " AND message_key IN (SELECT id::text FROM reservations WHERE schedule_id = ?)", Integer.class,
                scheduleId);
    }

    private String lotteryStatus() {
        return jdbc.queryForObject("SELECT status FROM lotteries WHERE id = ?", String.class, lotteryId);
    }

    private List<Map<String, Object>> ranks() {
        return jdbc.queryForList("SELECT user_id, status, draw_rank FROM lottery_entries WHERE lottery_id = ?"
                + " ORDER BY user_id", lotteryId);
    }
}
