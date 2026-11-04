package com.iseonjwa.reservation.reservation.application;

import com.iseonjwa.reservation.seathold.application.HoldSeatsCommand;
import com.iseonjwa.reservation.seathold.application.SeatHoldExpiryService;
import com.iseonjwa.reservation.seathold.application.SyncSeatHoldService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// 결제 결과 소비자가 추첨 처리기를 부르는지(ISE-150, D11)를 실제 PostgreSQL로 확인한다. 리스너는 테스트에서 꺼져 있어 서비스를 직접 부른다.
// 일반 선점으로 만든 예매의 출처를 LOTTERY로 바꾸고 응모를 SQL로 연결한다. 배정까지 잇는 흐름은 LotteryScenarioTest S12가 본다
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.config.import=", "spring.cloud.config.enabled=false", "eureka.client.enabled=false"})
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
class PaymentResultLotteryTest {
    private static final String MARK = "ISE-150 추첨 결제 결과 테스트";
    private static final long USER = 43L;
    private static final long SEAT_PRICE = 165000L;
    private static final BigDecimal TOTAL = BigDecimal.valueOf(SEAT_PRICE * 2);
    private static final long PAYMENT_ID = 9101L;

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    SyncSeatHoldService seatHold;
    @Autowired
    PaymentResultService paymentResults;
    @Autowired
    SeatHoldExpiryService expiryService;
    @Autowired
    PlatformTransactionManager transactionManager;

    private Long venueId;
    private Long scheduleId;
    private Long reservationId;
    private Long entryId;

    @BeforeEach
    void setUp() {
        venueId = jdbc.queryForObject("INSERT INTO venues (name, created_at, updated_at)"
                + " VALUES (?, now(), now()) RETURNING id", Long.class, MARK);
        var concertId = jdbc.queryForObject("INSERT INTO concerts (title, version, created_at, updated_at)"
                + " VALUES (?, 0, now(), now()) RETURNING id", Long.class, MARK);
        scheduleId = jdbc.queryForObject("INSERT INTO schedules (concert_id, venue_id, starts_at, open_at, version,"
                + " created_at, updated_at) VALUES (?, ?, now() + interval '1 day', now() - interval '1 minute',"
                + " 0, now(), now()) RETURNING id", Long.class, concertId, venueId);
        var seatIds = new ArrayList<Long>();
        for (var i = 1; i <= 2; i++) {
            var venueSeatId = jdbc.queryForObject("INSERT INTO venue_seats (venue_id, section, row_no, seat_no, grade,"
                    + " created_at, updated_at) VALUES (?, 'A', 1, ?, 'VIP', now(), now()) RETURNING id",
                    Long.class, venueId, i);
            seatIds.add(jdbc.queryForObject("INSERT INTO schedule_seats (schedule_id, venue_seat_id, section, grade,"
                    + " price, status, created_at, updated_at) VALUES (?, ?, 'A', 'VIP', ?, 'AVAILABLE', now(), now())"
                    + " RETURNING id", Long.class, scheduleId, venueSeatId, SEAT_PRICE));
        }
        reservationId = seatHold.hold(new HoldSeatsCommand(USER, UUID.randomUUID(), scheduleId, seatIds))
                .reservationId();
        jdbc.update("UPDATE reservations SET source = 'LOTTERY' WHERE id = ?", reservationId);
        var lotteryId = jdbc.queryForObject("INSERT INTO lotteries (schedule_id, source_grade, seat_count,"
                + " waitlist_count, max_per_entry, entry_start_at, entry_end_at, draw_at, seed, status, created_at,"
                + " updated_at) VALUES (?, 'VIP', 2, 0, 2, now() - interval '2 hour', now() - interval '2 minute',"
                + " now() - interval '1 minute', decode(repeat('ab', 32), 'hex'), 'DRAWN', now(), now())"
                + " RETURNING id", Long.class, scheduleId);
        entryId = jdbc.queryForObject("INSERT INTO lottery_entries (lottery_id, request_id, user_id, quantity, status,"
                + " draw_rank, reservation_id, payment_attempts, created_at, updated_at)"
                + " VALUES (?, ?, ?, 2, 'WON', 1, ?, 1, now(), now()) RETURNING id",
                Long.class, lotteryId, UUID.randomUUID(), USER, reservationId);
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM lottery_entries WHERE id = ?", entryId);
        jdbc.update("DELETE FROM lotteries WHERE schedule_id = ?", scheduleId);
        jdbc.update("DELETE FROM outbox_events WHERE topic = 'payment.command' AND message_key = ?",
                String.valueOf(reservationId));
        jdbc.update("DELETE FROM tickets WHERE reservation_seat_id IN"
                + " (SELECT id FROM reservation_seats WHERE reservation_id = ?)", reservationId);
        jdbc.update("DELETE FROM processed_events WHERE consumer_group = ?", PaymentResultService.CONSUMER_GROUP);
        jdbc.update("DELETE FROM seat_hold_requests WHERE schedule_id = ?", scheduleId);
        jdbc.update("DELETE FROM reservation_status_histories WHERE reservation_id = ?", reservationId);
        jdbc.update("DELETE FROM reservation_seats WHERE reservation_id = ?", reservationId);
        jdbc.update("DELETE FROM reservations WHERE id = ?", reservationId);
        jdbc.update("DELETE FROM user_schedule_quotas WHERE schedule_id = ?", scheduleId);
        jdbc.update("DELETE FROM schedule_seats WHERE schedule_id = ?", scheduleId);
        jdbc.update("DELETE FROM schedules WHERE id = ?", scheduleId);
        jdbc.update("DELETE FROM concerts WHERE title = ?", MARK);
        jdbc.update("DELETE FROM venue_seats WHERE venue_id = ?", venueId);
        jdbc.update("DELETE FROM venues WHERE id = ?", venueId);
    }

    @Test
    void 승인으로_예매가_확정되면_응모가_PAID가_된다() {
        paymentResults.handle(approved(PAYMENT_ID));

        assertEquals("CONFIRMED", reservationStatus());
        assertEquals("PAID", entryStatus());
    }

    @Test
    void 거절로_예매가_해제되면_응모가_FAILED가_되고_사유가_남는다() {
        paymentResults.handle(declined("CARD_DECLINED", USER));

        assertEquals("RELEASED", reservationStatus());
        assertEquals("FAILED", entryStatus());
        assertEquals("CARD_DECLINED", failReason());
    }

    @Test
    void NOT_SENT_거절은_응모를_WON으로_두고_예매_연결을_비워_재배정에_맡긴다() {
        paymentResults.handle(declined("NOT_SENT", USER));

        assertEquals("RELEASED", reservationStatus());
        assertEquals("WON", entryStatus());
        assertNull(linkedReservation());
        assertEquals(List.of("AVAILABLE", "AVAILABLE"), scheduleSeatStatuses());
    }

    @Test
    void 선점_기한_뒤_승인은_예매를_만료하고_응모를_FAILED로_바꾼_뒤_환불을_요청한다() {
        jdbc.update("UPDATE reservations SET hold_expires_at = now() - interval '1 hour' WHERE id = ?", reservationId);

        paymentResults.handle(approved(PAYMENT_ID));

        assertEquals("EXPIRED", reservationStatus());
        assertEquals("FAILED", entryStatus());
        assertEquals("HOLD_EXPIRED", failReason());
        assertEquals(1, refundRequests());
    }

    @Test
    void 같은_승인_이벤트가_두_번_와도_한_번만_반영한다() {
        var event = approved(PAYMENT_ID);

        paymentResults.handle(event);
        var paidAt = entryUpdatedAt();
        paymentResults.handle(event);

        assertEquals("PAID", entryStatus());
        assertEquals(paidAt, entryUpdatedAt());
        assertEquals(2, ticketCount());
    }

    @Test
    void 같은_거절_이벤트가_두_번_와도_한_번만_반영한다() {
        var event = declined("CARD_DECLINED", USER);

        paymentResults.handle(event);
        var failedAt = entryUpdatedAt();
        paymentResults.handle(event);

        assertEquals("FAILED", entryStatus());
        assertEquals(failedAt, entryUpdatedAt());
    }

    // 재발행은 eventId가 달라 처리 이력으로 걸러지지 않는다. 이때는 예매 상태 분기와 조건부 UPDATE가 막는다
    @Test
    void 이벤트_ID가_달라도_같은_결과면_응모를_다시_바꾸지_않는다() {
        paymentResults.handle(declined("CARD_DECLINED", USER));
        var failedAt = entryUpdatedAt();

        paymentResults.handle(declined("OTHER", USER));

        assertEquals("CARD_DECLINED", failReason());
        assertEquals(failedAt, entryUpdatedAt());
    }

    @Test
    void 확정된_뒤에_온_거절은_응모를_바꾸지_않는다() {
        paymentResults.handle(approved(PAYMENT_ID));

        paymentResults.handle(declined("CARD_DECLINED", USER));

        assertEquals("CONFIRMED", reservationStatus());
        assertEquals("PAID", entryStatus());
    }

    // Note: 호출한 트랜잭션을 되돌린다. 처리기가 트랜잭션을 따로 열면 응모만 커밋되어 이 테스트가 실패한다
    @Test
    void 승인_반영이_되돌려지면_예매와_응모가_함께_되돌아간다() {
        var event = approved(PAYMENT_ID);

        rollingBack(() -> paymentResults.handle(event));

        assertEquals("HELD", reservationStatus());
        assertEquals("WON", entryStatus());
        assertEquals(0, processedCount(event));
    }

    @Test
    void 거절_반영이_되돌려지면_예매와_응모가_함께_되돌아간다() {
        var event = declined("CARD_DECLINED", USER);

        rollingBack(() -> paymentResults.handle(event));

        assertEquals("HELD", reservationStatus());
        assertEquals("WON", entryStatus());
        assertEquals(0, processedCount(event));
    }

    // 예매가 HELD로 남아 만료 스케줄러가 회수한다. 응모는 그때 처리기가 바꾼다
    @Test
    void 금액이_다른_승인은_환불만_요청하고_응모를_바꾸지_않는다() {
        paymentResults.handle(mismatchedApproval());

        assertEquals("HELD", reservationStatus());
        assertEquals("WON", entryStatus());
        assertEquals(1, refundRequests());
    }

    @Test
    void 금액이_다른_승인으로_HELD에_남은_예매는_1회_연장_뒤_회수되어_응모가_FAILED가_된다() {
        paymentResults.handle(mismatchedApproval());

        extendThroughScheduler();
        assertEquals("WON", entryStatus(), "연장 중에는 응모를 바꾸지 않는다");
        expireThroughScheduler();

        assertEquals("EXPIRED", reservationStatus());
        assertEquals("FAILED", entryStatus());
        assertEquals("HOLD_EXPIRED", failReason());
        assertEquals(List.of("AVAILABLE", "AVAILABLE"), scheduleSeatStatuses());
    }

    // D12가 지키려는 흐름: 결과가 늦어 연장한 예매에 연장된 기한 안의 승인이 오면 확정한다
    @Test
    void 연장된_예매에_원래_기한_뒤_승인이_오면_확정하고_응모가_PAID가_된다() {
        extendThroughScheduler();

        paymentResults.handle(approved(PAYMENT_ID));

        assertEquals("CONFIRMED", reservationStatus());
        assertEquals("PAID", entryStatus());
        assertEquals(0, refundRequests());
    }

    @Test
    void 스케줄러가_만료한_뒤_온_승인은_환불만_요청하고_응모_사유를_유지한다() {
        extendThroughScheduler();
        expireThroughScheduler();

        paymentResults.handle(approved(PAYMENT_ID));

        assertEquals("EXPIRED", reservationStatus());
        assertEquals("HOLD_EXPIRED", failReason());
        assertEquals(1, refundRequests());
    }

    @Test
    void 스케줄러가_만료한_뒤_온_거절은_응모_사유를_바꾸지_않는다() {
        extendThroughScheduler();
        expireThroughScheduler();

        paymentResults.handle(declined("CARD_DECLINED", USER));

        assertEquals("EXPIRED", reservationStatus());
        assertEquals("HOLD_EXPIRED", failReason());
        assertEquals(0, refundRequests());
    }

    // 연결된 응모가 없는 추첨 예매는 데이터 오류다. 예매 확정은 되돌리지 않고 사람이 보게 로그만 남긴다
    @Test
    void 연결된_응모가_없는_추첨_예매는_확정하고_오류_로그를_남긴다(CapturedOutput output) {
        jdbc.update("UPDATE lottery_entries SET reservation_id = NULL WHERE id = ?", entryId);

        paymentResults.handle(approved(PAYMENT_ID));

        assertEquals("CONFIRMED", reservationStatus());
        assertEquals("WON", entryStatus());
        assertTrue(output.getOut().contains("event=LOTTERY_RESULT_NOT_APPLIED"));
    }

    @Test
    void 사용자가_다른_거절은_응모를_바꾸지_않는다() {
        paymentResults.handle(declined("CARD_DECLINED", USER + 1));

        assertEquals("HELD", reservationStatus());
        assertEquals("WON", entryStatus());
    }

    @Test
    void 이미_해제된_예매에_온_승인은_환불만_요청하고_응모를_바꾸지_않는다() {
        jdbc.update("UPDATE reservations SET status = 'RELEASED' WHERE id = ?", reservationId);

        paymentResults.handle(approved(PAYMENT_ID));

        assertEquals("WON", entryStatus());
        assertEquals(1, refundRequests());
    }

    // 일반 예매에 연결된 응모는 실제로 없다. 분기가 출처를 보는지 확인하려고 일부러 연결해 둔다
    @Test
    void 일반_예매의_승인은_처리기를_부르지_않는다() {
        jdbc.update("UPDATE reservations SET source = 'GENERAL' WHERE id = ?", reservationId);

        paymentResults.handle(approved(PAYMENT_ID));

        assertEquals("CONFIRMED", reservationStatus());
        assertEquals("WON", entryStatus());
    }

    @Test
    void 일반_예매의_거절은_처리기를_부르지_않는다() {
        jdbc.update("UPDATE reservations SET source = 'GENERAL' WHERE id = ?", reservationId);

        paymentResults.handle(declined("CARD_DECLINED", USER));

        assertEquals("RELEASED", reservationStatus());
        assertEquals("WON", entryStatus());
    }

    @Test
    void 일반_예매의_기한_뒤_승인은_처리기를_부르지_않는다() {
        jdbc.update("UPDATE reservations SET source = 'GENERAL', hold_expires_at = now() - interval '1 hour'"
                + " WHERE id = ?", reservationId);

        paymentResults.handle(approved(PAYMENT_ID));

        assertEquals("EXPIRED", reservationStatus());
        assertEquals("WON", entryStatus());
    }

    private void rollingBack(Runnable action) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            action.run();
            status.setRollbackOnly();
        });
    }

    // 첫 기한이 지나 스케줄러가 선점을 한 번 연장한다
    private void extendThroughScheduler() {
        jdbc.update("UPDATE reservations SET hold_expires_at = now() - interval '1 minute' WHERE id = ?",
                reservationId);
        assertFalse(expiryService.expire(reservationId), "첫 만료는 연장");
    }

    // 연장 여부는 "기한이 생성 + 31분 안인가"로 보므로 생성 시각도 함께 옮겨 연장한 뒤의 기한이 지난 상태를 만든다
    private void expireThroughScheduler() {
        jdbc.update("UPDATE reservations SET created_at = now() - interval '2 hour',"
                + " hold_expires_at = now() - interval '1 minute' WHERE id = ?", reservationId);
        assertTrue(expiryService.expire(reservationId));
    }

    private PaymentResult mismatchedApproval() {
        return PaymentResult.approved(UUID.randomUUID(), PAYMENT_ID, reservationId, USER, BigDecimal.valueOf(1000),
                "KRW", Instant.now());
    }

    private PaymentResult approved(long paymentId) {
        return PaymentResult.approved(UUID.randomUUID(), paymentId, reservationId, USER, TOTAL, "KRW", Instant.now());
    }

    private PaymentResult declined(String reasonCode, long userId) {
        return PaymentResult.declined(UUID.randomUUID(), PAYMENT_ID, reservationId, userId, reasonCode);
    }

    private String reservationStatus() {
        return jdbc.queryForObject("SELECT status FROM reservations WHERE id = ?", String.class, reservationId);
    }

    private String entryStatus() {
        return jdbc.queryForObject("SELECT status FROM lottery_entries WHERE id = ?", String.class, entryId);
    }

    private String failReason() {
        return jdbc.queryForObject("SELECT fail_reason FROM lottery_entries WHERE id = ?", String.class, entryId);
    }

    private Long linkedReservation() {
        return jdbc.queryForObject("SELECT reservation_id FROM lottery_entries WHERE id = ?", Long.class, entryId);
    }

    private Instant entryUpdatedAt() {
        return jdbc.queryForObject("SELECT updated_at FROM lottery_entries WHERE id = ?", Instant.class, entryId);
    }

    private List<String> scheduleSeatStatuses() {
        return jdbc.queryForList("SELECT status FROM schedule_seats WHERE schedule_id = ? ORDER BY id",
                String.class, scheduleId);
    }

    private int ticketCount() {
        return jdbc.queryForObject("SELECT count(*) FROM tickets t JOIN reservation_seats rs"
                + " ON rs.id = t.reservation_seat_id WHERE rs.reservation_id = ?", Integer.class, reservationId);
    }

    private int refundRequests() {
        return jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE topic = 'payment.command'"
                + " AND event_type = 'RefundRequested' AND message_key = ?", Integer.class,
                String.valueOf(reservationId));
    }

    private int processedCount(PaymentResult event) {
        return jdbc.queryForObject("SELECT count(*) FROM processed_events WHERE event_id = ?", Integer.class,
                event.getEventId());
    }
}
