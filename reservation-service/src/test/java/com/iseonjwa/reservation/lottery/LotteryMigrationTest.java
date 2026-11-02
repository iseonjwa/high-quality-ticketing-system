package com.iseonjwa.reservation.lottery;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Note: 제약 위반 뒤에는 PostgreSQL이 트랜잭션을 중단 상태로 만든다.
// 테스트마다 위반을 하나만 확인하고, 클래스 전체 롤백으로 데이터를 남기지 않는다.
@SpringBootTest(properties = {"spring.config.import=", "spring.cloud.config.enabled=false", "eureka.client.enabled=false"})
@ActiveProfiles("test")
@Transactional
class LotteryMigrationTest {
    private static final byte[] SEED = new byte[32];

    @Autowired
    JdbcTemplate jdbc;

    private Long scheduleId;

    @BeforeEach
    void setUp() {
        Long venueId = jdbc.queryForObject(
                "INSERT INTO venues (name, created_at, updated_at) VALUES ('KSPO DOME', now(), now()) RETURNING id",
                Long.class);
        Long concertId = jdbc.queryForObject(
                "INSERT INTO concerts (title, version, created_at, updated_at)"
                        + " VALUES ('콘서트', 0, now(), now()) RETURNING id",
                Long.class);
        scheduleId = jdbc.queryForObject(
                "INSERT INTO schedules (concert_id, venue_id, starts_at, open_at, version, created_at, updated_at)"
                        + " VALUES (?, ?, now() + interval '10 day', now() + interval '7 day', 0, now(), now())"
                        + " RETURNING id",
                Long.class, concertId, venueId);
    }

    @Test
    void 추첨_제약과_인덱스가_카탈로그에_존재한다() {
        var lotteryConstraints = names("SELECT conname FROM pg_constraint WHERE conrelid = 'lotteries'::regclass");
        assertTrue(lotteryConstraints.containsAll(List.of(
                "uq_lotteries_schedule_id", "chk_lotteries_source_grade", "chk_lotteries_seat_count",
                "chk_lotteries_waitlist_count", "chk_lotteries_max_per_entry", "chk_lotteries_period",
                "chk_lotteries_seed", "chk_lotteries_status")),
                "누락된 추첨 제약이 있다: " + lotteryConstraints);

        var entryConstraints = names("SELECT conname FROM pg_constraint WHERE conrelid = 'lottery_entries'::regclass");
        assertTrue(entryConstraints.containsAll(List.of(
                "uq_lottery_entries_request_id", "uq_lottery_entries_lottery_user",
                "chk_lottery_entries_quantity", "chk_lottery_entries_payment_attempts",
                "chk_lottery_entries_status")),
                "누락된 응모 제약이 있다: " + entryConstraints);

        var entryIndexes = names("SELECT indexname FROM pg_indexes WHERE tablename = 'lottery_entries'");
        assertTrue(entryIndexes.containsAll(List.of(
                "idx_lottery_entries_lottery_status_draw_rank", "idx_lottery_entries_won_unassigned")),
                "누락된 응모 인덱스가 있다: " + entryIndexes);
    }

    @Test
    void 같은_회차에_추첨을_두_개_만들_수_없다() {
        insertLottery("S", SEED);

        assertViolation("uq_lotteries_schedule_id", () -> insertLottery("R", SEED));
    }

    @Test
    void LOTTERY_등급에서_추첨석을_뗄_수_없다() {
        assertViolation("chk_lotteries_source_grade", () -> insertLottery("LOTTERY", SEED));
    }

    @Test
    void 시드는_32바이트여야_한다() {
        assertViolation("chk_lotteries_seed", () -> insertLottery("S", new byte[8]));
    }

    @Test
    void 응모_마감이_추첨_시각보다_늦을_수_없다() {
        assertViolation("chk_lotteries_period", () -> jdbc.update("""
                INSERT INTO lotteries (schedule_id, source_grade, seat_count, waitlist_count, max_per_entry,
                                       entry_start_at, entry_end_at, draw_at, seed, status, created_at, updated_at)
                VALUES (?, 'S', 100, 50, 2, now() + interval '1 day', now() + interval '3 day',
                        now() + interval '2 day', ?, 'OPEN', now(), now())""",
                scheduleId, SEED));
    }

    @Test
    void 같은_사용자는_취소한_뒤에도_같은_추첨에_다시_응모할_수_없다() {
        Long lotteryId = insertLottery("S", SEED);
        insertEntry(lotteryId, 7L, "CANCELED");

        assertViolation("uq_lottery_entries_lottery_user", () -> insertEntry(lotteryId, 7L, "ENTERED"));
    }

    @Test
    void 다른_사용자는_같은_추첨에_응모할_수_있다() {
        Long lotteryId = insertLottery("S", SEED);
        insertEntry(lotteryId, 7L, "ENTERED");
        insertEntry(lotteryId, 8L, "ENTERED");

        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM lottery_entries WHERE lottery_id = ?", Integer.class, lotteryId);
        assertEquals(2, count);
    }

    private Long insertLottery(String sourceGrade, byte[] seed) {
        return jdbc.queryForObject("""
                INSERT INTO lotteries (schedule_id, source_grade, seat_count, waitlist_count, max_per_entry,
                                       entry_start_at, entry_end_at, draw_at, seed, status, created_at, updated_at)
                VALUES (?, ?, 100, 50, 2, now() + interval '1 day', now() + interval '2 day',
                        now() + interval '3 day', ?, 'OPEN', now(), now())
                RETURNING id""",
                Long.class, scheduleId, sourceGrade, seed);
    }

    private void insertEntry(Long lotteryId, Long userId, String status) {
        jdbc.update("""
                INSERT INTO lottery_entries (lottery_id, request_id, user_id, quantity, status, created_at, updated_at)
                VALUES (?, ?, ?, 2, ?, now(), now())""",
                lotteryId, UUID.randomUUID(), userId, status);
    }

    private List<String> names(String sql) {
        return jdbc.queryForList(sql, String.class);
    }

    private static void assertViolation(String constraint, Runnable action) {
        var exception = assertThrows(DataIntegrityViolationException.class, action::run);
        assertTrue(exception.getMessage().contains(constraint), "기대한 제약 " + constraint + ": " + exception.getMessage());
    }
}
