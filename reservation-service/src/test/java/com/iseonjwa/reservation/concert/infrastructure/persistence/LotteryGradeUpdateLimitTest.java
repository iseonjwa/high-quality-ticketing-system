package com.iseonjwa.reservation.concert.infrastructure.persistence;

import com.iseonjwa.reservation.concert.domain.entity.SeatGrade;
import com.iseonjwa.reservation.concert.domain.repository.ScheduleSeatRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

// 추첨석 전환이 실행 계획과 상관없이 LIMIT을 지키는지 확인한다(근거: docs/learning/kangmin/qna/2026-11-04-update-limit-for-update-rescan.md).
// Note: 플래너가 테이블을 1행으로 믿으면 IN (SELECT ... LIMIT n FOR UPDATE)의 서브쿼리를 Nested Loop 안쪽에서 바깥 행마다 다시 실행한다.
// 통계는 pg_class를 직접 고쳐 만든다. ANALYZE는 결과를 트랜잭션과 무관하게 기록하지만 이 UPDATE는 테스트가 끝나면 함께 롤백된다.
// 대조군은 고치기 전 쿼리가 이 조건에서 실제로 넘치는지 확인한다. 대조군이 실패하면 조건이 재현되지 않은 것이므로 회귀 테스트의 통과도 믿을 수 없다.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.config.import=", "spring.cloud.config.enabled=false", "eureka.client.enabled=false"})
@ActiveProfiles("test")
@Transactional
class LotteryGradeUpdateLimitTest {
    private static final long ACTOR_ID = 9004L;
    private static final String MARK = "추첨석 전환 LIMIT 테스트";

    private static final String ORIGINAL_CONVERT_SQL = """
            UPDATE schedule_seats
            SET grade = 'LOTTERY', updated_at = now(), updated_by = CAST(? AS BIGINT)
            WHERE id IN (
                SELECT id FROM schedule_seats
                WHERE schedule_id = ? AND grade = ?
                  AND status = 'AVAILABLE' AND deleted_at IS NULL
                ORDER BY id
                LIMIT ?
                FOR UPDATE)
            """;
    private static final String ORIGINAL_REVERT_SQL = """
            UPDATE schedule_seats
            SET grade = ?, updated_at = now(), updated_by = CAST(? AS BIGINT)
            WHERE id IN (
                SELECT id FROM schedule_seats
                WHERE schedule_id = ? AND grade = 'LOTTERY' AND deleted_at IS NULL
                ORDER BY id DESC
                LIMIT ?
                FOR UPDATE)
            """;

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    ScheduleSeatRepository scheduleSeatRepository;

    private Long scheduleId;
    private List<Long> seatIds;

    @BeforeEach
    void setUp() {
        Long concertId = jdbc.queryForObject("INSERT INTO concerts (title, version, created_at, updated_at)"
                + " VALUES (?, 0, now(), now()) RETURNING id", Long.class, MARK);
        Long venueId = jdbc.queryForObject("INSERT INTO venues (name, created_at, updated_at)"
                + " VALUES (?, now(), now()) RETURNING id", Long.class, MARK);
        jdbc.update("INSERT INTO venue_seats (venue_id, section, row_no, seat_no, grade, created_at, updated_at)"
                + " VALUES (?, 'B', 1, 1, 'S', now(), now()), (?, 'B', 1, 2, 'S', now(), now()),"
                + " (?, 'B', 1, 3, 'S', now(), now())", venueId, venueId, venueId);
        scheduleId = jdbc.queryForObject("INSERT INTO schedules (concert_id, venue_id, starts_at, open_at, version,"
                + " created_at, updated_at) VALUES (?, ?, now() + interval '30 day', now() + interval '10 day', 0,"
                + " now(), now()) RETURNING id", Long.class, concertId, venueId);
        jdbc.update("INSERT INTO schedule_seats (schedule_id, venue_seat_id, section, grade, price, status,"
                + " created_at, updated_at) SELECT ?, vs.id, vs.section, vs.grade, 121000, 'AVAILABLE', now(), now()"
                + " FROM venue_seats vs WHERE vs.venue_id = ? ORDER BY vs.id", scheduleId, venueId);
        seatIds = jdbc.queryForList("SELECT id FROM schedule_seats WHERE schedule_id = ? ORDER BY id",
                Long.class, scheduleId);
    }

    @Test
    void 대조군_고치기_전_전환_쿼리는_LIMIT_1이어도_3석을_바꾼다() {
        arrangeForConvert();

        int converted = jdbc.update(ORIGINAL_CONVERT_SQL, ACTOR_ID, scheduleId, "S", 1);

        assertEquals(3, converted);
    }

    @Test
    void 통계가_1행이어도_전환은_요청한_수만큼만_ID_순으로_바꾼다() {
        arrangeForConvert();

        int converted = scheduleSeatRepository.convertToLottery(scheduleId, SeatGrade.S, 1, ACTOR_ID);

        assertEquals(1, converted);
        assertEquals(List.of(seatIds.get(0)), seatIdsOfGrade("LOTTERY"));
    }

    @Test
    void 대조군_고치기_전_되돌리기_쿼리는_LIMIT_1이어도_3석을_바꾼다() {
        arrangeForRevert();

        int reverted = jdbc.update(ORIGINAL_REVERT_SQL, "S", ACTOR_ID, scheduleId, 1);

        assertEquals(3, reverted);
    }

    @Test
    void 통계가_1행이어도_되돌리기는_요청한_수만큼만_큰_ID부터_바꾼다() {
        arrangeForRevert();

        int reverted = scheduleSeatRepository.revertFromLottery(scheduleId, SeatGrade.S, 1, ACTOR_ID);

        assertEquals(1, reverted);
        assertEquals(List.of(seatIds.get(2)), seatIdsOfGrade("S"));
    }

    private void arrangeForConvert() {
        requirePhysicalOrder(seatIds);
        makePlannerBelieveTableHasOneRow();
    }

    // Note: 되돌리기는 ID가 큰 좌석부터 고른다. 물리 순서가 ID 오름차순이면 바깥 스캔이 마지막 행에서야 일치해 다시 실행해도 넘치지 않는다.
    // 넘치는 경우를 재현하려고 큰 ID부터 한 건씩 고쳐 새 행 버전이 ID 내림차순으로 놓이게 한다.
    private void arrangeForRevert() {
        List<Long> descending = seatIds.reversed();
        for (Long seatId : descending) {
            jdbc.update("UPDATE schedule_seats SET grade = 'LOTTERY' WHERE id = ?", seatId);
        }
        requirePhysicalOrder(descending);
        makePlannerBelieveTableHasOneRow();
    }

    // 빈 공간을 재사용해 행이 다른 페이지로 갈리면 스캔 순서가 달라져 재현 조건이 아니므로, 실패 대신 건너뛴다
    private void requirePhysicalOrder(List<Long> expected) {
        List<Long> physical = jdbc.queryForList("SELECT id FROM schedule_seats WHERE schedule_id = ? ORDER BY ctid",
                Long.class, scheduleId);
        assumeTrue(expected.equals(physical), () -> "행의 물리 순서가 재현 조건과 다르다: " + physical);
    }

    private void makePlannerBelieveTableHasOneRow() {
        jdbc.update("UPDATE pg_class SET reltuples = 1,"
                + " relpages = GREATEST(pg_relation_size(oid) / current_setting('block_size')::int, 1)"
                + " WHERE oid = 'schedule_seats'::regclass");
    }

    private List<Long> seatIdsOfGrade(String grade) {
        return jdbc.queryForList("SELECT id FROM schedule_seats WHERE schedule_id = ? AND grade = ? ORDER BY id",
                Long.class, scheduleId, grade);
    }
}
