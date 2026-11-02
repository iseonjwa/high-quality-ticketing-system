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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// 예비자 승계와 승계 마감(ISE-93, D13·D14). 스케줄러는 끄고 서비스를 직접 부른다.
@SpringBootTest(properties = {"spring.config.import=", "spring.cloud.config.enabled=false", "eureka.client.enabled=false"})
@ActiveProfiles("test")
class LotterySuccessionTest {
    private static final String MARK = "추첨 승계 테스트";

    @Autowired
    LotterySuccessionService successionService;
    @Autowired
    TransactionTemplate tx;
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
        jdbc.update("DELETE FROM schedules WHERE id = ?", scheduleId);
        jdbc.update("DELETE FROM concerts WHERE title = ?", MARK);
        jdbc.update("DELETE FROM venues WHERE id = ?", venueId);
    }

    // 완료 기준: 실패 2건 동시 → 예비자 2명 순서대로, 좌석 수 초과 없음. 좌석 4 = 남은 당첨 2 + 실패 2
    @Test
    void 실패한_자리만큼_예비자를_순위_순으로_당첨시킨다() {
        insert("WON", 1, 1);
        insert("PAID", 1, 2);
        insert("FAILED", 1, 3);
        insert("FAILED", 1, 4);
        Long fifth = insert("WAITLISTED", 1, 5);
        Long sixth = insert("WAITLISTED", 1, 6);
        Long seventh = insert("WAITLISTED", 1, 7);

        assertEquals(2, successionService.succeed(lotteryId));

        assertEquals(List.of(fifth, sixth), idsOf("WON").subList(1, 3));
        assertEquals(List.of(seventh), idsOf("WAITLISTED"));
        assertEquals(4, takenSeats());
        assertEquals(0, successionService.succeed(lotteryId));
    }

    // 매수가 남은 좌석보다 많은 예비자는 건너뛰고 그대로 예비자로 남는다(D13, SeatAllocator.succeed와 같은 규칙)
    @Test
    void 남은_좌석보다_매수가_많은_예비자는_건너뛴다() {
        insert("WON", 2, 1);
        insert("WON", 1, 2);
        insert("FAILED", 1, 3);
        Long twoTickets = insert("WAITLISTED", 2, 4);
        Long oneTicket = insert("WAITLISTED", 1, 5);

        assertEquals(1, successionService.succeed(lotteryId));

        assertTrue(idsOf("WON").contains(oneTicket));
        assertEquals(List.of(twoTickets), idsOf("WAITLISTED"));
    }

    // 완료 기준·변이 테스트: 다른 워커가 추첨 행을 잡고 있으면 기다리지 않고 건너뛴다. 잠금을 빼면 이 테스트가 실패한다
    @Test
    void 다른_워커가_추첨_행을_잡고_있으면_승계하지_않고_건너뛴다() throws Exception {
        insert("FAILED", 1, 1);
        Long waitlisted = insert("WAITLISTED", 1, 2);
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var otherWorker = CompletableFuture.runAsync(() -> tx.executeWithoutResult(status -> {
            jdbc.queryForList("SELECT id FROM lotteries WHERE id = ? FOR UPDATE", lotteryId);
            locked.countDown();
            await(release);
        }));
        assertTrue(locked.await(5, TimeUnit.SECONDS));

        int promotedWhileLocked = successionService.succeed(lotteryId);
        release.countDown();
        otherWorker.get(5, TimeUnit.SECONDS);

        assertEquals(0, promotedWhileLocked);
        assertEquals(List.of(waitlisted), idsOf("WAITLISTED"));
        assertEquals(1, successionService.succeed(lotteryId));
    }

    // 완료 기준: 워커 2개 동시 실행에도 좌석 수를 넘지 않는다
    @Test
    void 워커_두_개가_동시에_돌아도_좌석_수를_넘지_않는다() throws Exception {
        insert("WON", 1, 1);
        insert("FAILED", 1, 2);
        insert("FAILED", 1, 3);
        for (int rank = 4; rank <= 9; rank++) {
            insert("WAITLISTED", 1, rank);
        }

        var first = CompletableFuture.supplyAsync(() -> successionService.succeed(lotteryId));
        var second = CompletableFuture.supplyAsync(() -> successionService.succeed(lotteryId));
        int promoted = first.get(10, TimeUnit.SECONDS) + second.get(10, TimeUnit.SECONDS);
        promoted += successionService.succeed(lotteryId);

        assertEquals(3, promoted);
        assertEquals(4, takenSeats());
    }

    // 완료 기준·변이 테스트: 승계 마감(추첨 후 30분) 뒤에는 승계하지 않고, 남은 예비자는 LOST가 된다
    @Test
    void 승계_마감이_지나면_승계하지_않고_남은_예비자를_LOST로_바꾼다() {
        jdbc.update("UPDATE lotteries SET draw_at = now() - interval '31 minute',"
                + " entry_end_at = now() - interval '40 minute' WHERE id = ?", lotteryId);
        insert("FAILED", 1, 1);
        Long waitlisted = insert("WAITLISTED", 1, 2);

        assertEquals(0, successionService.succeed(lotteryId));
        assertTrue(successionService.closeExpiredWaitlists() >= 1);

        assertEquals("LOST", jdbc.queryForObject("SELECT status FROM lottery_entries WHERE id = ?", String.class,
                waitlisted));
    }

    private Long insert(String status, int quantity, int drawRank) {
        return jdbc.queryForObject("INSERT INTO lottery_entries (lottery_id, request_id, user_id, quantity, status,"
                + " draw_rank, created_at, updated_at) VALUES (?, gen_random_uuid(), ?, ?, ?, ?, now(), now())"
                + " RETURNING id", Long.class, lotteryId, 8800L + drawRank, quantity, status, drawRank);
    }

    private List<Long> idsOf(String status) {
        return jdbc.queryForList("SELECT id FROM lottery_entries WHERE lottery_id = ? AND status = ?"
                + " ORDER BY draw_rank", Long.class, lotteryId, status);
    }

    private int takenSeats() {
        return jdbc.queryForObject("SELECT coalesce(sum(quantity), 0) FROM lottery_entries WHERE lottery_id = ?"
                + " AND status IN ('WON', 'PAID')", Integer.class, lotteryId);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}
