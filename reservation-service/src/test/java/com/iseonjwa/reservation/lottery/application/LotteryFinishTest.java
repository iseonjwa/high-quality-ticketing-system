package com.iseonjwa.reservation.lottery.application;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

// 추첨 종결과 추첨석 반환(ISE-91, D9·D14). 스케줄러는 끄고 서비스를 직접 부른다.
@SpringBootTest(properties = {"spring.config.import=", "spring.cloud.config.enabled=false", "eureka.client.enabled=false"})
@ActiveProfiles("test")
class LotteryFinishTest {
    private static final String MARK = "추첨 종결 테스트";

    @Autowired
    LotteryFinishService finishService;
    @Autowired
    TransactionTemplate tx;
    @Autowired
    JdbcTemplate jdbc;

    private Long venueId;
    private Long scheduleId;
    private Long lotteryId;

    // 좌석 6석: 1~4번 LOTTERY(AVAILABLE·HELD·SOLD·AVAILABLE), 5~6번 일반 S
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
                + " SELECT ?, 'B', 1, g, 'S', now(), now() FROM generate_series(1, 6) g", venueId);
        jdbc.update("INSERT INTO schedule_seats (schedule_id, venue_seat_id, section, grade, price, status,"
                + " created_at, updated_at) SELECT ?, vs.id, vs.section,"
                + " CASE WHEN vs.seat_no <= 4 THEN 'LOTTERY' ELSE 'S' END, 121000,"
                + " CASE vs.seat_no WHEN 2 THEN 'HELD' WHEN 3 THEN 'SOLD' ELSE 'AVAILABLE' END, now(), now()"
                + " FROM venue_seats vs WHERE vs.venue_id = ? ORDER BY vs.id", scheduleId, venueId);
        lotteryId = jdbc.queryForObject("INSERT INTO lotteries (schedule_id, source_grade, seat_count, waitlist_count,"
                + " max_per_entry, entry_start_at, entry_end_at, draw_at, seed, status, created_at, updated_at)"
                + " VALUES (?, 'S', 4, 10, 2, now() - interval '2 hour', now() - interval '10 minute',"
                + " now() - interval '1 minute', ?, 'DRAWN', now(), now()) RETURNING id",
                Long.class, scheduleId, new byte[32]);
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM lottery_entries WHERE lottery_id = ?", lotteryId);
        jdbc.update("DELETE FROM lotteries WHERE id = ?", lotteryId);
        jdbc.update("DELETE FROM schedule_seats WHERE schedule_id = ?", scheduleId);
        jdbc.update("DELETE FROM schedules WHERE id = ?", scheduleId);
        jdbc.update("DELETE FROM concerts WHERE title = ?", MARK);
        jdbc.update("DELETE FROM venue_seats WHERE venue_id = ?", venueId);
        jdbc.update("DELETE FROM venues WHERE id = ?", venueId);
    }

    // 완료 기준: 종결 → 모든 LOTTERY 좌석이 상태와 상관없이 원래 등급으로, 좌석 상태는 그대로
    @Test
    void 끝나지_않은_응모가_없으면_DONE으로_바꾸고_추첨석을_모두_원래_등급으로_되돌린다() {
        insert("PAID", 1);
        insert("FAILED", 2);
        insert("LOST", 3);
        long before = finishService.countRemainingLotterySeats();

        assertTrue(finishService.findFinishDueIds().contains(lotteryId));
        assertEquals(Optional.of(4), finishService.finish(lotteryId));

        assertEquals("DONE", lotteryStatus());
        assertEquals(List.of("S", "S", "S", "S", "S", "S"), seatColumn("grade"));
        assertEquals(List.of("AVAILABLE", "HELD", "SOLD", "AVAILABLE", "AVAILABLE", "AVAILABLE"),
                seatColumn("status"));
        assertEquals(before - 4, finishService.countRemainingLotterySeats());
        assertEquals(Optional.empty(), finishService.finish(lotteryId));
    }

    // 완료 기준·변이 테스트: 미종결 WON이 남아 있으면 종결하지 않는다
    @Test
    void 결제가_끝나지_않은_당첨이_남아_있으면_종결하지_않는다() {
        insert("PAID", 1);
        insert("WON", 2);

        assertFalse(finishService.findFinishDueIds().contains(lotteryId));
        assertEquals(Optional.empty(), finishService.finish(lotteryId));

        assertEquals("DRAWN", lotteryStatus());
        assertEquals(4, lotterySeatsOfSchedule());
    }

    // 예비자는 승계로 WON이 될 수 있어 승계 마감(LOST로 바뀜) 전에는 종결하지 않는다(D14)
    @Test
    void 승계될_수_있는_예비자가_남아_있으면_종결하지_않는다() {
        insert("FAILED", 1);
        insert("WAITLISTED", 2);

        assertEquals(Optional.empty(), finishService.finish(lotteryId));

        assertEquals("DRAWN", lotteryStatus());
        assertEquals(4, lotterySeatsOfSchedule());
    }

    // 승계 워커와 같은 추첨 행 잠금을 쓴다. 다른 워커가 잡고 있으면 기다리지 않고 건너뛴다
    @Test
    void 다른_워커가_추첨_행을_잡고_있으면_건너뛴다() throws Exception {
        insert("PAID", 1);
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var otherWorker = CompletableFuture.runAsync(() -> tx.executeWithoutResult(status -> {
            jdbc.queryForList("SELECT id FROM lotteries WHERE id = ? FOR UPDATE", lotteryId);
            locked.countDown();
            await(release);
        }));
        assertTrue(locked.await(5, TimeUnit.SECONDS));

        var whileLocked = finishService.finish(lotteryId);
        release.countDown();
        otherWorker.get(5, TimeUnit.SECONDS);

        assertEquals(Optional.empty(), whileLocked);
        assertEquals("DRAWN", lotteryStatus());
        assertEquals(Optional.of(4), finishService.finish(lotteryId));
    }

    // 완료 기준: 반환이 종결과 같은 트랜잭션. 반환 뒤 실패하면 DONE도 함께 되돌아간다
    @Test
    void 종결_트랜잭션이_롤백되면_DONE과_반환이_함께_취소된다() {
        insert("PAID", 1);

        assertThrows(IllegalStateException.class, () -> tx.executeWithoutResult(status -> {
            finishService.finish(lotteryId);
            throw new IllegalStateException("반환 뒤 실패");
        }));

        assertEquals("DRAWN", lotteryStatus());
        assertEquals(4, lotterySeatsOfSchedule());
    }

    private void insert(String status, int drawRank) {
        jdbc.update("INSERT INTO lottery_entries (lottery_id, request_id, user_id, quantity, status, draw_rank,"
                + " created_at, updated_at) VALUES (?, gen_random_uuid(), ?, 1, ?, ?, now(), now())",
                lotteryId, 8900L + drawRank, status, drawRank);
    }

    private String lotteryStatus() {
        return jdbc.queryForObject("SELECT status FROM lotteries WHERE id = ?", String.class, lotteryId);
    }

    private List<String> seatColumn(String column) {
        return jdbc.queryForList("SELECT " + column + " FROM schedule_seats WHERE schedule_id = ? ORDER BY id",
                String.class, scheduleId);
    }

    private int lotterySeatsOfSchedule() {
        return jdbc.queryForObject("SELECT count(*) FROM schedule_seats WHERE schedule_id = ? AND grade = 'LOTTERY'",
                Integer.class, scheduleId);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}
