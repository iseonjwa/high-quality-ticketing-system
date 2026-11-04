package com.iseonjwa.reservation.concert.infrastructure.persistence;

import com.iseonjwa.reservation.concert.domain.entity.Schedule;
import com.iseonjwa.reservation.concert.domain.repository.ScheduleRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// 대기열이 쓰는 회차 조회: 잠금 없는 단건 조회(ISE-139), until까지 열리고 아직 시작하지 않은 회차(ISE-140)
@SpringBootTest(properties = {"spring.config.import=", "spring.cloud.config.enabled=false", "eureka.client.enabled=false"})
@ActiveProfiles("test")
class JpaScheduleRepositoryTest {
    private static final String MARK = "회차 조회 테스트";
    private static final Instant STARTS_AT = Instant.parse("2026-11-20T10:00:00Z");
    private static final Instant OPEN_AT = Instant.parse("2026-11-01T11:00:00Z");

    @Autowired
    ScheduleRepository scheduleRepository;
    @Autowired
    JdbcTemplate jdbc;

    private Long venueId;
    private Long concertId;
    private Long scheduleId;

    @BeforeEach
    void setUp() {
        venueId = jdbc.queryForObject("INSERT INTO venues (name, created_at, updated_at)"
                + " VALUES (?, now(), now()) RETURNING id", Long.class, MARK);
        concertId = jdbc.queryForObject("INSERT INTO concerts (title, version, created_at, updated_at)"
                + " VALUES (?, 0, now(), now()) RETURNING id", Long.class, MARK);
        scheduleId = insertSchedule(OPEN_AT, STARTS_AT);
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM schedules WHERE venue_id = ?", venueId);
        jdbc.update("DELETE FROM concerts WHERE title = ?", MARK);
        jdbc.update("DELETE FROM venues WHERE id = ?", venueId);
    }

    @Test
    void 회차를_id로_읽으면_예매_오픈과_공연_시작_시각을_준다() {
        var schedule = scheduleRepository.findById(scheduleId).orElseThrow();

        assertEquals(OPEN_AT, schedule.getOpenAt());
        assertEquals(STARTS_AT, schedule.getStartsAt());
    }

    @Test
    void 없거나_지운_회차는_비어_있다() {
        jdbc.update("UPDATE schedules SET deleted_at = now() WHERE id = ?", scheduleId);

        assertTrue(scheduleRepository.findById(scheduleId).isEmpty());
        assertTrue(scheduleRepository.findById(Long.MAX_VALUE).isEmpty());
    }

    @Test
    void until까지_예매가_열리고_아직_시작하지_않은_회차만_오픈_순으로_준다() {
        Instant now = Instant.parse("2030-01-10T00:00:00Z");
        Instant until = Instant.parse("2030-01-11T00:00:00Z");
        // now 정각에 공연이 시작해 이미 마감
        Long startsAtNow = insertSchedule(Instant.parse("2030-01-01T00:00:00Z"), now);
        // 이미 열려 판매 중
        Long alreadyOpen = insertSchedule(Instant.parse("2030-01-05T00:00:00Z"), Instant.parse("2030-01-15T00:00:00Z"));
        // until 정각에 열린다(포함)
        Long opensAtUntil = insertSchedule(until, Instant.parse("2030-01-20T00:00:00Z"));
        // until 뒤에 열린다
        Long opensAfterUntil = insertSchedule(until.plusSeconds(1), Instant.parse("2030-01-25T00:00:00Z"));
        Long deleted = insertSchedule(Instant.parse("2030-01-05T00:00:00Z"), Instant.parse("2030-01-15T00:00:00Z"));
        jdbc.update("UPDATE schedules SET deleted_at = now() WHERE id = ?", deleted);

        var ids = scheduleRepository.findOpenBy(until, now).stream()
                .map(Schedule::getId)
                .filter(id -> List.of(startsAtNow, alreadyOpen, opensAtUntil, opensAfterUntil, deleted).contains(id))
                .toList();

        assertEquals(List.of(alreadyOpen, opensAtUntil), ids);
    }

    private Long insertSchedule(Instant openAt, Instant startsAt) {
        return jdbc.queryForObject("INSERT INTO schedules (concert_id, venue_id, starts_at, open_at, version,"
                + " created_at, updated_at) VALUES (?, ?, ?, ?, 0, now(), now()) RETURNING id", Long.class,
                concertId, venueId, Timestamp.from(startsAt), Timestamp.from(openAt));
    }
}
