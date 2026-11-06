package com.iseonjwa.reservation.lottery.application;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// 결제 결과 지표(ISE-94). 실제 트랜잭션에서 커밋된 결과만 집계되는지 확인한다.
@SpringBootTest(properties = {"spring.config.import=", "spring.cloud.config.enabled=false", "eureka.client.enabled=false"})
@ActiveProfiles("test")
class LotteryPaymentMetricsTest {
    private static final String MARK = "추첨 지표 테스트";

    @Autowired
    LotteryPaymentResultHandler resultHandler;
    @Autowired
    LotteryAssignmentService assignmentService;
    @Autowired
    LotteryFinishService finishService;
    @Autowired
    MeterRegistry registry;
    @Autowired
    TransactionTemplate tx;
    @Autowired
    JdbcTemplate jdbc;

    private Long venueId;
    private Long scheduleId;
    private Long lotteryId;
    private Long entryId;
    private Long reservationId;

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
                + " VALUES (?, 'B', 1, 1, 'S', now(), now())", venueId);
        jdbc.update("INSERT INTO schedule_seats (schedule_id, venue_seat_id, section, grade, price, status,"
                + " created_at, updated_at) SELECT ?, vs.id, vs.section, 'LOTTERY', 100000, 'AVAILABLE', now(), now()"
                + " FROM venue_seats vs WHERE vs.venue_id = ?", scheduleId, venueId);
        lotteryId = jdbc.queryForObject("INSERT INTO lotteries (schedule_id, source_grade, seat_count, waitlist_count,"
                + " max_per_entry, entry_start_at, entry_end_at, draw_at, seed, status, created_at, updated_at)"
                + " VALUES (?, 'S', 1, 0, 2, now() - interval '3 hour', now() - interval '2 hour',"
                + " now() - interval '5 minute', ?, 'DRAWN', now(), now()) RETURNING id",
                Long.class, scheduleId, new byte[32]);
        entryId = jdbc.queryForObject("INSERT INTO lottery_entries (lottery_id, request_id, user_id, quantity, status,"
                + " draw_rank, created_at, updated_at) VALUES (?, gen_random_uuid(), 8901, 1, 'WON', 1, now(), now())"
                + " RETURNING id", Long.class, lotteryId);
        assertEquals(entryId, assignmentService.assignNext().orElseThrow());
        reservationId = jdbc.queryForObject("SELECT reservation_id FROM lottery_entries WHERE id = ?",
                Long.class, entryId);
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

    // 변이 테스트 대상: 커밋을 기다리지 않고 세면 트랜잭션 안의 첫 단언이 실패한다
    @Test
    void 결제_성공은_커밋된_뒤에_추첨_시각부터의_시간으로_집계된다() {
        long before = settlementCount("paid");
        double secondsBefore = settlementSeconds("paid");

        tx.executeWithoutResult(status -> {
            resultHandler.onPaid(reservationId);
            assertEquals(before, settlementCount("paid"));
        });

        assertEquals(before + 1, settlementCount("paid"));
        double seconds = settlementSeconds("paid") - secondsBefore;
        assertTrue(seconds >= 300 && seconds < 360, "추첨 5분 뒤 반영한 시간이어야 한다: " + seconds);
    }

    // 롤백 뒤 Kafka가 다시 전달하면 같은 결과가 두 번 집계되지 않아야 한다
    @Test
    void 트랜잭션이_롤백되면_집계하지_않는다() {
        long before = settlementCount("paid");

        tx.executeWithoutResult(status -> {
            resultHandler.onPaid(reservationId);
            status.setRollbackOnly();
        });

        assertEquals(before, settlementCount("paid"));
        assertEquals("WON", entryStatus());
    }

    @Test
    void 결제_거절은_결과와_사유별로_집계한다() {
        long settled = settlementCount("failed");
        double reason = failures("BILLING_KEY_MISSING");

        resultHandler.onFailed(reservationId, "BILLING_KEY_MISSING");

        assertEquals("FAILED", entryStatus());
        assertEquals(settled + 1, settlementCount("failed"));
        assertEquals(reason + 1, failures("BILLING_KEY_MISSING"));
    }

    // 라벨 값이 외부 입력으로 늘어나면 Prometheus 시계열이 함께 늘어난다
    @Test
    void 모르는_거절_사유는_OTHER로_묶는다() {
        double other = failures("OTHER");

        resultHandler.onFailed(reservationId, "PG_RAW_CODE_1234");

        assertEquals(other + 1, failures("OTHER"));
        assertEquals(0, registry.find("lottery.entry.failures").tag("reason", "PG_RAW_CODE_1234").counters().size());
    }

    @Test
    void NOT_SENT_재시도는_종료로_세지_않고_재시도로_센다() {
        long settled = settlementCount("failed");
        double retries = counter("lottery.payment.retries");

        resultHandler.onFailed(reservationId, "NOT_SENT");

        assertEquals("WON", entryStatus());
        assertEquals(settled, settlementCount("failed"));
        assertEquals(retries + 1, counter("lottery.payment.retries"));
    }

    @Test
    void 같은_결과가_다시_와도_한_번만_집계하고_미반영으로_센다() {
        resultHandler.onPaid(reservationId);
        long settled = settlementCount("paid");
        double notApplied = counter("lottery.result.not.applied");

        resultHandler.onPaid(reservationId);

        assertEquals(settled, settlementCount("paid"));
        assertEquals(notApplied + 1, counter("lottery.result.not.applied"));
    }

    @Test
    void 진행_상태는_종결되지_않은_추첨과_결제_대기_당첨자를_읽는다() {
        var progress = finishService.readProgress();

        assertTrue(progress.unfinishedLotteries() >= 1);
        assertTrue(progress.oldestUnfinishedSeconds() >= 300);
        assertTrue(progress.unsettledWinners() >= 1);
        assertTrue(progress.remainingLotterySeats() >= 1);

        resultHandler.onPaid(reservationId);

        assertEquals(progress.unsettledWinners() - 1, finishService.readProgress().unsettledWinners());
    }

    private long settlementCount(String result) {
        List<Timer> timers = List.copyOf(registry.find("lottery.settlement.duration").tag("result", result).timers());
        return timers.isEmpty() ? 0 : timers.getFirst().count();
    }

    private double settlementSeconds(String result) {
        List<Timer> timers = List.copyOf(registry.find("lottery.settlement.duration").tag("result", result).timers());
        return timers.isEmpty() ? 0 : timers.getFirst().totalTime(TimeUnit.SECONDS);
    }

    private double failures(String reason) {
        Counter found = registry.find("lottery.entry.failures").tag("reason", reason).counter();
        return found == null ? 0 : found.count();
    }

    private double counter(String name) {
        return registry.get(name).counter().count();
    }

    private String entryStatus() {
        return jdbc.queryForObject("SELECT status FROM lottery_entries WHERE id = ?", String.class, entryId);
    }
}
