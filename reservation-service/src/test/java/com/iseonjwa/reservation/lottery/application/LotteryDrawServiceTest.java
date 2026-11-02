package com.iseonjwa.reservation.lottery.application;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// 응모 마감·추첨 실행(D8, details.md 4절). 스케줄러는 끄고 서비스를 직접 부른다.
@SpringBootTest(properties = {"spring.config.import=", "spring.cloud.config.enabled=false", "eureka.client.enabled=false"})
@ActiveProfiles("test")
class LotteryDrawServiceTest {
    private static final String MARK = "추첨 실행 테스트";

    @Autowired
    LotteryDrawService drawService;
    @Autowired
    JdbcTemplate jdbc;

    private Long concertId;
    private Long venueId;

    @BeforeEach
    void setUp() {
        concertId = jdbc.queryForObject("INSERT INTO concerts (title, version, created_at, updated_at)"
                + " VALUES (?, 0, now(), now()) RETURNING id", Long.class, MARK);
        venueId = jdbc.queryForObject("INSERT INTO venues (name, created_at, updated_at)"
                + " VALUES (?, now(), now()) RETURNING id", Long.class, MARK);
    }

    @AfterEach
    void cleanUp() {
        String schedulesOfMark = "(SELECT s.id FROM schedules s JOIN concerts c ON c.id = s.concert_id"
                + " WHERE c.title = ?)";
        jdbc.update("DELETE FROM lottery_entries WHERE lottery_id IN (SELECT id FROM lotteries WHERE schedule_id IN "
                + schedulesOfMark + ")", MARK);
        jdbc.update("DELETE FROM lotteries WHERE schedule_id IN " + schedulesOfMark, MARK);
        jdbc.update("DELETE FROM schedule_seats WHERE schedule_id IN " + schedulesOfMark, MARK);
        jdbc.update("DELETE FROM schedules WHERE concert_id IN (SELECT id FROM concerts WHERE title = ?)", MARK);
        jdbc.update("DELETE FROM concerts WHERE title = ?", MARK);
        jdbc.update("DELETE FROM venue_seats WHERE venue_id IN (SELECT id FROM venues WHERE name = ?)", MARK);
        jdbc.update("DELETE FROM venues WHERE name = ?", MARK);
    }

    @Test
    void 마감이_지난_OPEN_추첨만_CLOSED로_바꾼다() {
        Long due = insertLottery("OPEN", "now() - interval '2 hour'", "now() - interval '1 minute'", 2, 0, 60);
        Long notDue = insertLottery("OPEN", "now() - interval '1 hour'", "now() + interval '1 hour'", 2, 0, 120);

        drawService.closeDueLotteries();

        assertEquals("CLOSED", statusOf(due));
        assertEquals("OPEN", statusOf(notDue));
    }

    // 좌석 3석, 예비 1명. 1매·2매·1매·1매 응모 4건 중 3석이 차면 남은 응모는 예비 1명과 낙첨으로 나뉜다
    @Test
    void 추첨하면_당첨_매수가_좌석_수를_넘지_않고_예비와_낙첨으로_나뉜다() {
        Long lotteryId = insertLottery("CLOSED", "now() - interval '2 hour'", "now() - interval '1 minute'", 3, 1, 0);
        insertEntries(lotteryId, List.of(1, 2, 1, 1, 2));
        jdbc.update("UPDATE lottery_entries SET status = 'CANCELED' WHERE id = (SELECT max(id) FROM lottery_entries"
                + " WHERE lottery_id = ?)", lotteryId);

        assertTrue(drawService.draw(lotteryId));

        assertEquals("DRAWN", statusOf(lotteryId));
        var counts = jdbc.queryForList("SELECT status, count(*) AS n, coalesce(sum(quantity), 0) AS seats,"
                + " count(draw_rank) AS ranked FROM lottery_entries WHERE lottery_id = ? GROUP BY status", lotteryId);
        Map<String, Map<String, Object>> byStatus = new java.util.HashMap<>();
        counts.forEach(row -> byStatus.put((String) row.get("status"), row));
        assertTrue(((Number) byStatus.get("WON").get("seats")).intValue() <= 3);
        assertEquals(1, ((Number) byStatus.get("WAITLISTED").get("n")).intValue());
        assertEquals(1, ((Number) byStatus.get("CANCELED").get("n")).intValue());
        assertEquals(4, ((Number) byStatus.get("WON").get("n")).intValue()
                + ((Number) byStatus.get("WAITLISTED").get("n")).intValue()
                + ((Number) byStatus.getOrDefault("LOST", Map.of("n", 0)).get("n")).intValue());
        // 당첨·예비만 순위를 저장하고 낙첨·취소에는 저장하지 않는다(11/2 결정)
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM lottery_entries WHERE lottery_id = ?"
                + " AND status IN ('LOST', 'CANCELED') AND draw_rank IS NOT NULL", Integer.class, lotteryId));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM lottery_entries WHERE lottery_id = ?"
                + " AND status IN ('WON', 'WAITLISTED') AND draw_rank IS NULL", Integer.class, lotteryId));
    }

    // 완료 기준: 추첨 2회 호출 → 1회만 반영
    @Test
    void 같은_추첨을_두_번_부르면_한_번만_반영한다() {
        Long lotteryId = insertLottery("CLOSED", "now() - interval '2 hour'", "now() - interval '1 minute'", 2, 2, 0);
        insertEntries(lotteryId, List.of(1, 1, 1, 1, 1, 1));
        assertTrue(drawService.draw(lotteryId));
        var firstResult = snapshot(lotteryId);
        jdbc.update("UPDATE lottery_entries SET status = 'ENTERED', draw_rank = NULL WHERE lottery_id = ?",
                lotteryId);

        boolean secondRun = drawService.draw(lotteryId);

        assertFalse(secondRun);
        assertEquals(6, jdbc.queryForObject("SELECT count(*) FROM lottery_entries WHERE lottery_id = ?"
                + " AND status = 'ENTERED'", Integer.class, lotteryId));
        assertEquals(6, firstResult.size());
    }

    @Test
    void 추첨_시각_전이면_추첨하지_않는다() {
        Long lotteryId = insertLottery("CLOSED", "now() - interval '2 hour'", "now() - interval '1 minute'", 2, 0, 60);
        insertEntries(lotteryId, List.of(1));

        assertFalse(drawService.draw(lotteryId));
        assertEquals("CLOSED", statusOf(lotteryId));
        assertFalse(drawService.findDrawDueIds().contains(lotteryId));
    }

    // 완료 기준: 응모 3만 행 추첨 시간 측정(상한 10초, numbers-and-measurement.md). 좌석 2,000석, 예비 1,000명
    @Test
    void 응모_3만_행_추첨을_10초_안에_끝낸다() {
        Long lotteryId = insertLottery("CLOSED", "now() - interval '2 hour'", "now() - interval '1 minute'", 2000,
                1000, 0);
        jdbc.update("INSERT INTO lottery_entries (lottery_id, request_id, user_id, quantity, status, created_at,"
                + " updated_at) SELECT ?, gen_random_uuid(), 1000000 + g, 1 + (g % 2), 'ENTERED', now(), now()"
                + " FROM generate_series(1, 30000) g", lotteryId);

        long started = System.nanoTime();
        assertTrue(drawService.draw(lotteryId));
        long elapsedMillis = (System.nanoTime() - started) / 1_000_000;

        System.out.println("[측정] 응모 30,000행·좌석 2,000석·예비 1,000명 추첨 시간: " + elapsedMillis + "ms");
        assertTrue(elapsedMillis < 10_000, "추첨 시간 " + elapsedMillis + "ms");
        assertEquals(1000, jdbc.queryForObject("SELECT count(*) FROM lottery_entries WHERE lottery_id = ?"
                + " AND status = 'WAITLISTED'", Integer.class, lotteryId));
        assertTrue(jdbc.queryForObject("SELECT sum(quantity) FROM lottery_entries WHERE lottery_id = ?"
                + " AND status = 'WON'", Integer.class, lotteryId) <= 2000);
    }

    // 회차마다 추첨석 seatCount개(한 열에 50석씩)를 LOTTERY로 만든다
    private Long insertLottery(String status, String entryStartSql, String entryEndSql, int seatCount,
                               int waitlistCount, int drawAfterMinutes) {
        Long scheduleId = jdbc.queryForObject("INSERT INTO schedules (concert_id, venue_id, starts_at, open_at,"
                + " version, created_at, updated_at) VALUES (?, ?, now() + interval '30 day',"
                + " now() + interval '10 day', 0, now(), now()) RETURNING id", Long.class, concertId, venueId);
        jdbc.update("INSERT INTO venue_seats (venue_id, section, row_no, seat_no, grade, created_at, updated_at)"
                + " SELECT ?, 'S' || ?, 1 + (g - 1) / 50, 1 + (g - 1) % 50, 'S', now(), now()"
                + " FROM generate_series(1, ?) g", venueId, scheduleId, seatCount);
        jdbc.update("INSERT INTO schedule_seats (schedule_id, venue_seat_id, section, grade, price, status,"
                + " created_at, updated_at) SELECT ?, vs.id, vs.section, 'LOTTERY', 121000, 'AVAILABLE', now(), now()"
                + " FROM venue_seats vs WHERE vs.venue_id = ? AND vs.section = 'S' || ? ORDER BY vs.id",
                scheduleId, venueId, scheduleId);
        return jdbc.queryForObject("INSERT INTO lotteries (schedule_id, source_grade, seat_count, waitlist_count,"
                + " max_per_entry, entry_start_at, entry_end_at, draw_at, seed, status, created_at, updated_at)"
                + " VALUES (?, 'S', ?, ?, 2, " + entryStartSql + ", " + entryEndSql + ","
                + " now() + make_interval(mins => ?), ?, ?, now(), now()) RETURNING id",
                Long.class, scheduleId, seatCount, waitlistCount, drawAfterMinutes, new byte[32], status);
    }

    private void insertEntries(Long lotteryId, List<Integer> quantities) {
        long userId = 5000;
        for (var quantity : quantities) {
            jdbc.update("INSERT INTO lottery_entries (lottery_id, request_id, user_id, quantity, status, created_at,"
                    + " updated_at) VALUES (?, gen_random_uuid(), ?, ?, 'ENTERED', now(), now())",
                    lotteryId, userId++, quantity);
        }
    }

    private String statusOf(Long lotteryId) {
        return jdbc.queryForObject("SELECT status FROM lotteries WHERE id = ?", String.class, lotteryId);
    }

    private List<Map<String, Object>> snapshot(Long lotteryId) {
        return jdbc.queryForList("SELECT id, status, draw_rank FROM lottery_entries WHERE lottery_id = ? ORDER BY id",
                lotteryId);
    }
}
