package com.iseonjwa.reservation.concert.infrastructure.persistence;

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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// 대기열 입장이 회차의 예매 오픈·공연 시작 시각을 읽는 잠금 없는 조회(ISE-139)
@SpringBootTest(properties = {"spring.config.import=", "spring.cloud.config.enabled=false", "eureka.client.enabled=false"})
@ActiveProfiles("test")
class JpaScheduleRepositoryTest {
    private static final String MARK = "회차 단건 조회 테스트";
    private static final Instant STARTS_AT = Instant.parse("2026-11-20T10:00:00Z");
    private static final Instant OPEN_AT = Instant.parse("2026-11-01T11:00:00Z");

    @Autowired
    ScheduleRepository scheduleRepository;
    @Autowired
    JdbcTemplate jdbc;

    private Long venueId;
    private Long scheduleId;

    @BeforeEach
    void setUp() {
        venueId = jdbc.queryForObject("INSERT INTO venues (name, created_at, updated_at)"
                + " VALUES (?, now(), now()) RETURNING id", Long.class, MARK);
        Long concertId = jdbc.queryForObject("INSERT INTO concerts (title, version, created_at, updated_at)"
                + " VALUES (?, 0, now(), now()) RETURNING id", Long.class, MARK);
        scheduleId = jdbc.queryForObject("INSERT INTO schedules (concert_id, venue_id, starts_at, open_at, version,"
                + " created_at, updated_at) VALUES (?, ?, ?, ?, 0, now(), now()) RETURNING id", Long.class,
                concertId, venueId, Timestamp.from(STARTS_AT), Timestamp.from(OPEN_AT));
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
}
