package com.iseonjwa.reservation.reservation.application;

import com.iseonjwa.reservation.seathold.application.HoldSeatsCommand;
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

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// 결제 결과 반영(ISE-73, ADR 0027)을 실제 PostgreSQL로 확인한다. 리스너는 테스트에서 꺼져 있어 서비스를 직접 부른다.
// 선점은 실제 선점 서비스로 만든다. 좌석 2석(165,000원씩), 예매 총액 330,000원이다
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.config.import=", "spring.cloud.config.enabled=false", "eureka.client.enabled=false"})
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
class PaymentResultServiceTest {
    private static final String MARK = "ISE-73 결제 결과 테스트";
    private static final long USER = 42L;
    private static final long SEAT_PRICE = 165000L;
    private static final BigDecimal TOTAL = BigDecimal.valueOf(SEAT_PRICE * 2);
    private static final long PAYMENT_ID = 9001L;

    private final List<Long> reservationIds = new ArrayList<>();

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    SyncSeatHoldService seatHold;
    @Autowired
    PaymentResultService paymentResults;

    private Long venueId;
    private Long scheduleId;
    private List<Long> seatIds;

    @BeforeEach
    void setUp() {
        venueId = jdbc.queryForObject("INSERT INTO venues (name, created_at, updated_at)"
                + " VALUES (?, now(), now()) RETURNING id", Long.class, MARK);
        var concertId = jdbc.queryForObject("INSERT INTO concerts (title, version, created_at, updated_at)"
                + " VALUES (?, 0, now(), now()) RETURNING id", Long.class, MARK);
        scheduleId = jdbc.queryForObject("INSERT INTO schedules (concert_id, venue_id, starts_at, open_at, version,"
                + " created_at, updated_at) VALUES (?, ?, now() + interval '1 day', now() - interval '1 minute',"
                + " 0, now(), now()) RETURNING id", Long.class, concertId, venueId);
        seatIds = new ArrayList<>();
        for (var i = 1; i <= 2; i++) {
            var venueSeatId = jdbc.queryForObject("INSERT INTO venue_seats (venue_id, section, row_no, seat_no, grade,"
                    + " created_at, updated_at) VALUES (?, 'A', 1, ?, 'VIP', now(), now()) RETURNING id",
                    Long.class, venueId, i);
            seatIds.add(jdbc.queryForObject("INSERT INTO schedule_seats (schedule_id, venue_seat_id, section, grade,"
                    + " price, status, created_at, updated_at) VALUES (?, ?, 'A', 'VIP', ?, 'AVAILABLE', now(), now())"
                    + " RETURNING id", Long.class, scheduleId, venueSeatId, SEAT_PRICE));
        }
    }

    @AfterEach
    void cleanUp() {
        for (var reservationId : reservationIds) {
            jdbc.update("DELETE FROM outbox_events WHERE topic = 'payment.command' AND message_key = ?",
                    String.valueOf(reservationId));
            jdbc.update("DELETE FROM tickets WHERE reservation_seat_id IN"
                    + " (SELECT id FROM reservation_seats WHERE reservation_id = ?)", reservationId);
        }
        jdbc.update("DELETE FROM processed_events WHERE consumer_group = ?", PaymentResultService.CONSUMER_GROUP);
        jdbc.update("DELETE FROM seat_hold_requests WHERE schedule_id = ?", scheduleId);
        jdbc.update("DELETE FROM reservation_status_histories WHERE reservation_id IN"
                + " (SELECT id FROM reservations WHERE schedule_id = ?)", scheduleId);
        jdbc.update("DELETE FROM reservation_seats WHERE reservation_id IN"
                + " (SELECT id FROM reservations WHERE schedule_id = ?)", scheduleId);
        jdbc.update("DELETE FROM reservations WHERE schedule_id = ?", scheduleId);
        jdbc.update("DELETE FROM user_schedule_quotas WHERE schedule_id = ?", scheduleId);
        jdbc.update("DELETE FROM schedule_seats WHERE schedule_id = ?", scheduleId);
        jdbc.update("DELETE FROM schedules WHERE venue_id = ?", venueId);
        jdbc.update("DELETE FROM concerts WHERE title = ?", MARK);
        jdbc.update("DELETE FROM venue_seats WHERE venue_id = ?", venueId);
        jdbc.update("DELETE FROM venues WHERE id = ?", venueId);
    }

    @Test
    void 선점_시간_안의_승인은_예매를_확정하고_좌석마다_티켓을_발급한다() {
        var reservationId = hold();

        paymentResults.handle(approved(reservationId, PAYMENT_ID, TOTAL, Instant.now()));

        assertEquals("CONFIRMED", reservationStatus(reservationId));
        assertEquals(PAYMENT_ID, jdbc.queryForObject("SELECT payment_id FROM reservations WHERE id = ?", Long.class,
                reservationId));
        assertEquals(List.of("CONFIRMED", "CONFIRMED"), reservationSeatStatuses(reservationId));
        assertEquals(List.of("SOLD", "SOLD"), scheduleSeatStatuses());
        assertEquals(List.of("ISSUED", "ISSUED"), ticketStatuses(reservationId));
        assertEquals(2, heldCount());
        assertEquals(1, historyCount(reservationId, "CONFIRMED", "PAYMENT_APPROVED"));
        assertEquals(List.of(), refundReasons(reservationId));
    }

    @Test
    void 같은_이벤트가_두_번_와도_한_번만_반영한다() {
        var reservationId = hold();
        var event = approved(reservationId, PAYMENT_ID, TOTAL, Instant.now());

        paymentResults.handle(event);
        paymentResults.handle(event);

        assertEquals(2, ticketStatuses(reservationId).size());
        assertEquals(1, historyCount(reservationId, "CONFIRMED", "PAYMENT_APPROVED"));
    }

    @Test
    void 확정된_예매에_같은_결제의_승인이_다시_오면_그대로_두고_다른_결제면_환불을_요청한다() {
        var reservationId = hold();
        paymentResults.handle(approved(reservationId, PAYMENT_ID, TOTAL, Instant.now()));

        paymentResults.handle(approved(reservationId, PAYMENT_ID, TOTAL, Instant.now()));
        assertEquals(List.of(), refundReasons(reservationId));

        paymentResults.handle(approved(reservationId, PAYMENT_ID + 1, TOTAL, Instant.now()));
        assertEquals("CONFIRMED", reservationStatus(reservationId));
        assertEquals(List.of("DUPLICATE_APPROVAL"), refundReasons(reservationId));
        assertEquals(2, ticketStatuses(reservationId).size());
    }

    @Test
    void 선점_기한_뒤에_승인되면_만료하고_좌석과_매수를_돌려준_뒤_환불을_요청한다() {
        var reservationId = hold();
        passHoldExpiry(reservationId);

        paymentResults.handle(approved(reservationId, PAYMENT_ID, TOTAL, Instant.now()));

        assertEquals("EXPIRED", reservationStatus(reservationId));
        assertEquals(List.of("RELEASED", "RELEASED"), reservationSeatStatuses(reservationId));
        assertEquals(List.of("AVAILABLE", "AVAILABLE"), scheduleSeatStatuses());
        assertEquals(0, heldCount());
        assertEquals(1, historyCount(reservationId, "EXPIRED", "EXPIRED_AFTER_APPROVAL"));
        assertEquals(List.of("EXPIRED_AFTER_APPROVAL"), refundReasons(reservationId));
        // 결제 서비스가 결제 행과 대조하는 값이다(ADR 0020)
        var payload = jdbc.queryForMap("SELECT payload->'payload'->>'paymentId' AS payment_id,"
                + " payload->'payload'->>'userId' AS user_id, payload->'payload'->>'amount' AS amount,"
                + " payload->'payload'->>'currency' AS currency, payload->>'producer' AS producer"
                + " FROM outbox_events WHERE topic = 'payment.command' AND message_key = ?",
                String.valueOf(reservationId));
        assertEquals(String.valueOf(PAYMENT_ID), payload.get("payment_id"));
        assertEquals(String.valueOf(USER), payload.get("user_id"));
        assertEquals("330000", payload.get("amount"));
        assertEquals("KRW", payload.get("currency"));
        assertEquals("reservation-service", payload.get("producer"));
    }

    @Test
    void 금액이_다른_승인은_확정하지_않고_환불을_요청한다() {
        var reservationId = hold();

        paymentResults.handle(approved(reservationId, PAYMENT_ID, BigDecimal.valueOf(1000), Instant.now()));

        assertEquals("HELD", reservationStatus(reservationId));
        assertEquals(List.of("HELD", "HELD"), scheduleSeatStatuses());
        assertEquals(List.of("NOT_CONFIRMABLE"), refundReasons(reservationId));
    }

    @Test
    void 이미_만료된_예매에_승인이_오면_환불만_요청한다() {
        var reservationId = hold();
        jdbc.update("UPDATE reservations SET status = 'EXPIRED' WHERE id = ?", reservationId);

        paymentResults.handle(approved(reservationId, PAYMENT_ID, TOTAL, Instant.now()));

        assertEquals("EXPIRED", reservationStatus(reservationId));
        assertEquals(List.of("EXPIRED_AFTER_APPROVAL"), refundReasons(reservationId));
    }

    @Test
    void 결제가_거절되면_예매를_해제하고_좌석과_매수를_돌려준다() {
        var reservationId = hold();

        paymentResults.handle(PaymentResult.declined(UUID.randomUUID(), PAYMENT_ID, reservationId, USER,
                "CARD_DECLINED"));

        assertEquals("RELEASED", reservationStatus(reservationId));
        assertEquals(List.of("RELEASED", "RELEASED"), reservationSeatStatuses(reservationId));
        assertEquals(List.of("AVAILABLE", "AVAILABLE"), scheduleSeatStatuses());
        assertEquals(0, heldCount());
        assertEquals(1, historyCount(reservationId, "RELEASED", "PAYMENT_DECLINED"));
        assertEquals(List.of(), refundReasons(reservationId));
    }

    @Test
    void 확정된_예매의_결제가_환불돼도_좌석을_풀지_않는다() {
        var reservationId = hold();
        paymentResults.handle(approved(reservationId, PAYMENT_ID, TOTAL, Instant.now()));

        paymentResults.handle(PaymentResult.refunded(UUID.randomUUID(), PAYMENT_ID, reservationId, USER, TOTAL,
                "KRW", Instant.now()));

        assertEquals("CONFIRMED", reservationStatus(reservationId));
        assertEquals(List.of("SOLD", "SOLD"), scheduleSeatStatuses());
        assertEquals(List.of("ISSUED", "ISSUED"), ticketStatuses(reservationId));
    }

    // Loki 건별 알림 규칙(PaymentResultAnomalyEvent)이 읽는 줄 형식이다. 바꾸면 규칙과 script/check-loki-rules.sh를 함께 바꾼다
    @Test
    void 확정_예매의_결제가_환불되면_격리_줄을_알림_규칙이_읽는_형식으로_남긴다(CapturedOutput output) {
        var reservationId = hold();
        paymentResults.handle(approved(reservationId, PAYMENT_ID, TOTAL, Instant.now()));
        var refundEventId = UUID.randomUUID();

        paymentResults.handle(PaymentResult.refunded(refundEventId, PAYMENT_ID, reservationId, USER, TOTAL,
                "KRW", Instant.now()));

        var line = output.getAll().lines()
                .filter(text -> text.contains("event=PAYMENT_RESULT_ANOMALY "))
                .filter(text -> text.contains("reservationId=" + reservationId + " "))
                .findFirst().orElseThrow(() -> new AssertionError("격리 줄이 없습니다"));
        var body = line.substring(line.indexOf("event=PAYMENT_RESULT_ANOMALY "));
        assertTrue(body.startsWith("event=PAYMENT_RESULT_ANOMALY type=REFUNDED eventId=" + refundEventId + " "), body);
        assertTrue(body.contains(" reservationId=" + reservationId + " paymentId=" + PAYMENT_ID + " reason="), body);
    }

    @Test
    void 없는_예매의_결과는_처리한_것으로_기록만_한다() {
        var event = approved(Long.MAX_VALUE, PAYMENT_ID, TOTAL, Instant.now());

        paymentResults.handle(event);

        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM processed_events WHERE event_id = ?",
                Integer.class, event.getEventId()));
    }

    private Long hold() {
        var reservationId = seatHold.hold(new HoldSeatsCommand(USER, UUID.randomUUID(), scheduleId, seatIds))
                .reservationId();
        reservationIds.add(reservationId);
        return reservationId;
    }

    private static PaymentResult approved(Long reservationId, long paymentId, BigDecimal amount, Instant approvedAt) {
        return PaymentResult.approved(UUID.randomUUID(), paymentId, reservationId, USER, amount, "KRW", approvedAt);
    }

    // 5분을 기다리지 않고 선점 기한을 과거로 옮긴다. Docker DB 시계가 앞설 수 있어 넉넉히 뺀다(SeatHoldExpiryTest와 같다)
    private void passHoldExpiry(Long reservationId) {
        jdbc.update("UPDATE reservations SET hold_expires_at = now() - interval '1 hour' WHERE id = ?",
                reservationId);
    }

    private String reservationStatus(Long reservationId) {
        return jdbc.queryForObject("SELECT status FROM reservations WHERE id = ?", String.class, reservationId);
    }

    private List<String> reservationSeatStatuses(Long reservationId) {
        return jdbc.queryForList("SELECT status FROM reservation_seats WHERE reservation_id = ? ORDER BY id",
                String.class, reservationId);
    }

    private List<String> scheduleSeatStatuses() {
        return jdbc.queryForList("SELECT status FROM schedule_seats WHERE schedule_id = ? ORDER BY id",
                String.class, scheduleId);
    }

    private List<String> ticketStatuses(Long reservationId) {
        return jdbc.queryForList("SELECT t.status FROM tickets t JOIN reservation_seats rs"
                + " ON rs.id = t.reservation_seat_id WHERE rs.reservation_id = ? ORDER BY t.id", String.class,
                reservationId);
    }

    private int heldCount() {
        return jdbc.queryForObject("SELECT held_count FROM user_schedule_quotas WHERE user_id = ? AND schedule_id = ?",
                Integer.class, USER, scheduleId);
    }

    private int historyCount(Long reservationId, String toStatus, String reason) {
        return jdbc.queryForObject("SELECT count(*) FROM reservation_status_histories"
                + " WHERE reservation_id = ? AND from_status = 'HELD' AND to_status = ? AND reason = ?",
                Integer.class, reservationId, toStatus, reason);
    }

    private List<String> refundReasons(Long reservationId) {
        return jdbc.queryForList("SELECT payload->'payload'->>'reason' FROM outbox_events"
                + " WHERE topic = 'payment.command' AND event_type = 'RefundRequested' AND message_key = ?"
                + " ORDER BY id", String.class, String.valueOf(reservationId));
    }
}
