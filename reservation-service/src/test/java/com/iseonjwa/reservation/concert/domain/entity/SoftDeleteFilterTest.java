package com.iseonjwa.reservation.concert.domain.entity;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

// Flyway 스키마 위에서 soft delete 기본 필터(@SQLRestriction)가 실제로 어떻게 동작하는지 확인한다
@SpringBootTest(properties = {"spring.config.import=", "spring.cloud.config.enabled=false", "eureka.client.enabled=false"})
@ActiveProfiles("test")
class SoftDeleteFilterTest {
    @Autowired
    EntityManager em;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    TransactionTemplate tx;

    private Long deletedConcertId;
    private Long scheduleId;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM schedule_seats; DELETE FROM schedules; DELETE FROM venue_seats;"
                + " DELETE FROM concerts; DELETE FROM venues");
        Long venueId = jdbc.queryForObject(
                "INSERT INTO venues (name, created_at, updated_at) VALUES ('KSPO DOME', now(), now()) RETURNING id",
                Long.class);
        deletedConcertId = jdbc.queryForObject(
                "INSERT INTO concerts (title, version, created_at, updated_at, deleted_at)"
                        + " VALUES ('지운 공연', 0, now(), now(), now()) RETURNING id",
                Long.class);
        scheduleId = jdbc.queryForObject(
                "INSERT INTO schedules (concert_id, venue_id, starts_at, open_at, version, created_at, updated_at)"
                        + " VALUES (?, ?, now() + interval '2 day', now() + interval '1 day', 0, now(), now())"
                        + " RETURNING id",
                Long.class, deletedConcertId, venueId);
    }

    @Test
    void 지운_공연은_조회와_id_조회에서_빠진다() {
        tx.executeWithoutResult(status -> {
            assertEquals(0, em.createQuery("select c from Concert c", Concert.class).getResultList().size());
            assertNull(em.find(Concert.class, deletedConcertId));
        });
    }

    @Test
    void 회차에서_지운_공연을_따라가면_id는_보이지만_내용을_읽을_때_실패한다() {
        tx.executeWithoutResult(status -> {
            var concert = em.find(Schedule.class, scheduleId).getConcert();

            assertEquals(deletedConcertId, concert.getId());
            assertThrows(EntityNotFoundException.class, concert::getTitle);
        });
    }

    @Test
    void 지운_공연과_join_fetch하면_회차도_결과에서_빠진다() {
        tx.executeWithoutResult(status -> {
            var schedules = em.createQuery("select s from Schedule s join fetch s.concert where s.id = :id",
                            Schedule.class)
                    .setParameter("id", scheduleId)
                    .getResultList();

            assertEquals(0, schedules.size());
        });
    }
}
