package com.iseonjwa.reservation.lottery.application;

import com.iseonjwa.reservation.lottery.application.LotteryAssignmentService.AssignmentRejected;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

// 배정 워커와 결제 결과 처리기(ISE-90, D10·D12). 스케줄러는 끄고 서비스를 직접 부른다.
@SpringBootTest(properties = {"spring.config.import=", "spring.cloud.config.enabled=false", "eureka.client.enabled=false"})
@ActiveProfiles("test")
class LotteryAssignmentTest {
    private static final String MARK = "추첨 배정 테스트";

    @Autowired
    LotteryAssignmentService assignmentService;
    @Autowired
    LotteryPaymentResultHandler resultHandler;
    @Autowired
    TransactionTemplate tx;
    @Autowired
    JdbcTemplate jdbc;

    private Long venueId;
    private Long scheduleId;
    private Long lotteryId;

    // 추첨석 좌석 번호 1, 3, 4, 5, 6(한 열). 1과 3은 옆자리가 아니고 3·4가 첫 옆자리 쌍이다
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
                + " SELECT ?, 'B', 1, n, 'S', now(), now() FROM unnest(ARRAY[1, 3, 4, 5, 6]) n", venueId);
        jdbc.update("INSERT INTO schedule_seats (schedule_id, venue_seat_id, section, grade, price, status,"
                + " created_at, updated_at) SELECT ?, vs.id, vs.section, 'LOTTERY', 100000, 'AVAILABLE', now(), now()"
                + " FROM venue_seats vs WHERE vs.venue_id = ? ORDER BY vs.seat_no", scheduleId, venueId);
        lotteryId = insertDrawnLottery("now() - interval '1 minute'");
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

    @Test
    void 당첨자에게_옆자리_쌍으로_LOTTERY_예매를_만들어_응모에_연결한다() {
        Long entryId = insertWinner(7101L, 2, 1);

        assertEquals(entryId, assignmentService.assignNext().orElseThrow());

        var entry = entry(entryId);
        assertEquals("WON", entry.get("status"));
        assertEquals(1, ((Number) entry.get("payment_attempts")).intValue());
        Long reservationId = ((Number) entry.get("reservation_id")).longValue();
        assertEquals("LOTTERY", jdbc.queryForObject("SELECT source FROM reservations WHERE id = ?", String.class,
                reservationId));
        assertEquals(List.of(3, 4), jdbc.queryForList("SELECT vs.seat_no FROM reservation_seats rs"
                + " JOIN schedule_seats ss ON ss.id = rs.schedule_seat_id JOIN venue_seats vs ON vs.id = ss.venue_seat_id"
                + " WHERE rs.reservation_id = ? ORDER BY vs.seat_no", Integer.class, reservationId));

        // ISE-92 완료 기준: 당첨 예매 1건당 자동결제 요청 1건이 같은 트랜잭션의 outbox에 남는다
        var requests = billingRequests(entryId);
        assertEquals(1, requests.size());
        var request = requests.getFirst();
        assertEquals("payment.command", request.get("topic"));
        assertEquals(String.valueOf(reservationId), request.get("message_key"));
        assertEquals("PENDING", request.get("status"));
        assertEquals(String.valueOf(reservationId), request.get("reservation_id"));
        assertEquals("7101", request.get("user_id"));
        assertEquals(jdbc.queryForObject("SELECT total_amount FROM reservations WHERE id = ?", Long.class,
                reservationId).toString(), request.get("amount"));
        assertEquals("KRW", request.get("currency"));
    }

    // 완료 기준: 워커 중 종료 → 1건만 롤백, 다음 주기가 이어받음
    @Test
    void 배정_트랜잭션이_롤백되면_그_응모만_남고_다음_호출이_이어받는다() {
        Long first = insertWinner(7201L, 1, 1);
        Long second = insertWinner(7202L, 1, 2);

        tx.executeWithoutResult(status -> {
            assignmentService.assignNext();
            status.setRollbackOnly();
        });
        assertNull(entry(first).get("reservation_id"));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM reservations WHERE schedule_id = ?", Integer.class,
                scheduleId));
        assertEquals(0, billingRequests(first).size());

        assertEquals(first, assignmentService.assignNext().orElseThrow());
        assertEquals(second, assignmentService.assignNext().orElseThrow());
        assertTrue(assignmentService.assignNext().isEmpty());
    }

    // ISE-153: 남은 추첨석이 없으면 승계 마감 전에는 닫지 않고(좌석 반환을 기다림), 마감 뒤에만 NO_SEAT로 FAILED다
    @Test
    void 남은_추첨석이_모자라면_승계_마감까지_기다리고_그_뒤에_NO_SEAT로_FAILED를_남긴다() {
        jdbc.update("UPDATE schedule_seats SET status = 'SOLD' WHERE schedule_id = ? AND id <> (SELECT min(id)"
                + " FROM schedule_seats WHERE schedule_id = ?)", scheduleId, scheduleId);
        Long entryId = insertWinner(7301L, 2, 1);

        var rejected = assertThrows(AssignmentRejected.class, () -> assignmentService.assignNext());
        assertEquals(entryId, rejected.entryId());
        assertFalse(assignmentService.markRejected(rejected.entryId(), rejected.reason()), "마감 전에는 닫지 않는다");
        assertEquals("WON", entry(entryId).get("status"));
        assertNull(entry(entryId).get("reservation_id"));

        jdbc.update("UPDATE lotteries SET draw_at = now() - interval '31 minute',"
                + " entry_end_at = now() - interval '32 minute' WHERE id = ?", lotteryId);
        assertTrue(assignmentService.markRejected(rejected.entryId(), rejected.reason()));

        assertEquals("FAILED", entry(entryId).get("status"));
        assertEquals("NO_SEAT", entry(entryId).get("fail_reason"));
        assertEquals(0, billingRequests(entryId).size());
    }

    // 당첨 예매도 회차 매수 한도를 거친다. 거절된 배정의 매수·좌석 변경은 롤백된다
    @Test
    void 매수_한도를_넘는_당첨자는_PURCHASE_LIMIT_EXCEEDED로_FAILED다() {
        jdbc.update("INSERT INTO user_schedule_quotas (user_id, schedule_id, held_count, created_at, updated_at)"
                + " VALUES (7401, ?, 3, now(), now())", scheduleId);
        Long entryId = insertWinner(7401L, 2, 1);

        var rejected = assertThrows(AssignmentRejected.class, () -> assignmentService.assignNext());
        assignmentService.markRejected(rejected.entryId(), rejected.reason());

        assertEquals("PURCHASE_LIMIT_EXCEEDED", entry(entryId).get("fail_reason"));
        assertEquals(0, billingRequests(entryId).size());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM schedule_seats WHERE schedule_id = ?"
                + " AND status = 'HELD'", Integer.class, scheduleId));
    }

    // 완료 기준: 같은 결과 이벤트 2번 → 1번만 반영. 이미 끝난 응모는 다른 결과가 와도 바뀌지 않는다
    @Test
    void 결제_결과가_두_번_와도_한_번만_반영하고_끝난_응모는_바뀌지_않는다() {
        Long paid = insertWinner(7501L, 1, 1);
        Long failed = insertWinner(7502L, 1, 2);
        assignmentService.assignNext();
        assignmentService.assignNext();
        Long paidReservation = reservationOf(paid);
        Long failedReservation = reservationOf(failed);

        resultHandler.onPaid(paidReservation);
        resultHandler.onPaid(paidReservation);
        resultHandler.onFailed(paidReservation, "CARD_DECLINED");
        resultHandler.onFailed(failedReservation, "CARD_DECLINED");
        resultHandler.onPaid(failedReservation);

        assertEquals("PAID", entry(paid).get("status"));
        assertNull(entry(paid).get("fail_reason"));
        assertEquals("FAILED", entry(failed).get("status"));
        assertEquals("CARD_DECLINED", entry(failed).get("fail_reason"));
    }

    // 완료 기준: NOT_SENT → 새 예매로 1회 재시도, 2회째 실패면 FAILED(D12)
    @Test
    void NOT_SENT는_새_예매로_한_번_다시_시도하고_두_번째면_FAILED다() {
        Long entryId = insertWinner(7601L, 1, 1);
        assignmentService.assignNext();
        Long firstReservation = reservationOf(entryId);

        resultHandler.onFailed(firstReservation, "NOT_SENT");
        assertEquals("WON", entry(entryId).get("status"));
        assertNull(entry(entryId).get("reservation_id"));

        assignmentService.assignNext();
        Long secondReservation = reservationOf(entryId);
        assertNotEquals(firstReservation, secondReservation);
        assertEquals(2, ((Number) entry(entryId).get("payment_attempts")).intValue());
        // 재시도는 새 예매라 결제 요청도 새 키(reservationId)로 한 건 더 나간다
        assertEquals(List.of(String.valueOf(firstReservation), String.valueOf(secondReservation)),
                billingRequests(entryId).stream().map(row -> (String) row.get("message_key")).sorted().toList());

        resultHandler.onFailed(secondReservation, "NOT_SENT");
        assertEquals("FAILED", entry(entryId).get("status"));
        assertEquals("NOT_SENT", entry(entryId).get("fail_reason"));
    }

    // 승계 마감(추첨 후 30분, D13)이 지나면 우리 책임 실패도 재시도하지 않는다
    @Test
    void 승계_마감이_지나면_NOT_SENT도_바로_FAILED다() {
        jdbc.update("UPDATE lotteries SET draw_at = now() - interval '31 minute',"
                + " entry_end_at = now() - interval '40 minute', entry_start_at = now() - interval '2 hour'"
                + " WHERE id = ?", lotteryId);
        Long entryId = insertWinner(7701L, 1, 1);
        assignmentService.assignNext();

        resultHandler.onFailed(reservationOf(entryId), "NOT_SENT");

        assertEquals("FAILED", entry(entryId).get("status"));
    }

    @Test
    void 결제_결과_없이_선점이_만료되면_HOLD_EXPIRED로_FAILED다() {
        Long entryId = insertWinner(7801L, 1, 1);
        assignmentService.assignNext();

        resultHandler.onExpired(reservationOf(entryId));

        assertEquals("FAILED", entry(entryId).get("status"));
        assertEquals("HOLD_EXPIRED", entry(entryId).get("fail_reason"));
    }

    private Long insertDrawnLottery(String drawAtSql) {
        return jdbc.queryForObject("INSERT INTO lotteries (schedule_id, source_grade, seat_count, waitlist_count,"
                + " max_per_entry, entry_start_at, entry_end_at, draw_at, seed, status, created_at, updated_at)"
                + " VALUES (?, 'S', 5, 0, 2, now() - interval '2 hour', now() - interval '10 minute', " + drawAtSql
                + ", ?, 'DRAWN', now(), now()) RETURNING id", Long.class, scheduleId, new byte[32]);
    }

    private Long insertWinner(long userId, int quantity, int drawRank) {
        return jdbc.queryForObject("INSERT INTO lottery_entries (lottery_id, request_id, user_id, quantity, status,"
                + " draw_rank, created_at, updated_at) VALUES (?, gen_random_uuid(), ?, ?, 'WON', ?, now(), now())"
                + " RETURNING id", Long.class, lotteryId, userId, quantity, drawRank);
    }

    private List<Map<String, Object>> billingRequests(Long entryId) {
        return jdbc.queryForList("SELECT topic, message_key, status, payload -> 'payload' ->> 'reservationId' AS reservation_id,"
                + " payload -> 'payload' ->> 'userId' AS user_id, payload -> 'payload' ->> 'amount' AS amount,"
                + " payload -> 'payload' ->> 'currency' AS currency FROM outbox_events"
                + " WHERE event_type = 'BillingPaymentRequested' AND payload -> 'payload' ->> 'lotteryEntryId' = ?",
                String.valueOf(entryId));
    }

    private Map<String, Object> entry(Long entryId) {
        return jdbc.queryForMap("SELECT status, reservation_id, payment_attempts, fail_reason FROM lottery_entries"
                + " WHERE id = ?", entryId);
    }

    private Long reservationOf(Long entryId) {
        return ((Number) entry(entryId).get("reservation_id")).longValue();
    }
}
