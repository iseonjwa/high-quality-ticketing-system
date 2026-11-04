package com.iseonjwa.payment.messaging;

import com.iseonjwa.common.messaging.retention.RetentionCleanup;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

// Note: 정리 쿼리는 PostgreSQL 문법(어드바이저리 잠금, 행 값 IN)을 써서 common의 H2 테스트로 검증하지 못한다.
// outbox_events 전체와 test.로 시작하는 소비 그룹의 processed_events만 다룬다.
@SpringBootTest(properties = {"spring.config.import=", "spring.cloud.config.enabled=false", "eureka.client.enabled=false"})
@ActiveProfiles("test")
class RetentionCleanupTest {
    private static final String GROUP = "test.cleanup";
    private static final Instant NOW = Instant.parse("2026-11-10T00:00:00Z");
    private static final Instant CUTOFF = NOW.minus(7, ChronoUnit.DAYS);
    private static final Instant OLD = CUTOFF.minus(1, ChronoUnit.HOURS);
    private static final Instant RECENT = CUTOFF.plus(1, ChronoUnit.HOURS);

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    PlatformTransactionManager transactionManager;
    @Autowired
    DataSource dataSource;

    private RetentionCleanup cleanup;

    @BeforeEach
    void setUp() {
        cleanup = new RetentionCleanup(jdbc, transactionManager);
        clean();
    }

    @AfterEach
    void tearDown() {
        clean();
    }

    private void clean() {
        jdbc.update("DELETE FROM outbox_events");
        jdbc.update("DELETE FROM processed_events WHERE consumer_group LIKE 'test.%'");
    }

    @Test
    void 기간이_지난_SENT만_지우고_PENDING과_FAILED와_기간_안의_SENT는_남긴다() {
        outbox("SENT", OLD);
        outbox("SENT", RECENT);
        // 오래됐어도 지우면 안 된다: 전송 대기, 재시도 대기(next_attempt_at이 미래), 영구 실패.
        // sent_at이 기간 밖이어도 상태 조건이 지켜 주는지 보려고 sent_at을 채운다(되살린 행이 sent_at을 남길 수 있다)
        outbox("PENDING", OLD, OLD.minus(30, ChronoUnit.DAYS), OLD);
        outbox("PENDING", OLD, OLD, NOW.plus(1, ChronoUnit.HOURS));
        outbox("FAILED", OLD, OLD, OLD);

        assertEquals(1, cleanup.deleteSentOutbox(CUTOFF, 1_000));

        assertEquals(4, count("outbox_events"));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE status = 'SENT' AND sent_at < ?",
                Integer.class, Timestamp.from(CUTOFF)));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE status = 'SENT'", Integer.class));
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE status = 'PENDING'", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE status = 'FAILED'", Integer.class));
    }

    @Test
    void Outbox_정리는_배치_크기만큼만_지우고_오래된_것부터_지운다() {
        for (var i = 0; i < 5; i++) outbox("SENT", OLD.minus(i, ChronoUnit.MINUTES));

        assertEquals(2, cleanup.deleteSentOutbox(CUTOFF, 2));
        assertEquals(3, count("outbox_events"));
        // 남은 것 중 가장 오래된 시각이 지운 두 건보다 뒤다
        var oldestLeft = jdbc.queryForObject("SELECT min(sent_at) FROM outbox_events", Timestamp.class).toInstant();
        assertEquals(OLD.minus(2, ChronoUnit.MINUTES), oldestLeft);

        assertEquals(2, cleanup.deleteSentOutbox(CUTOFF, 2));
        assertEquals(1, cleanup.deleteSentOutbox(CUTOFF, 2));
        assertEquals(0, cleanup.deleteSentOutbox(CUTOFF, 2));
    }

    @Test
    void 처리_이력은_기간이_지난_것만_지운다() {
        var oldId = processed(OLD, GROUP);
        var oldOtherGroup = processed(oldId, OLD, GROUP + ".b");
        var recentId = processed(RECENT, GROUP);

        assertEquals(2, cleanup.deleteProcessedEvents(CUTOFF, 1_000));

        assertEquals(0, processedCount(oldId));
        assertEquals(0, processedCount(oldOtherGroup));
        assertEquals(1, processedCount(recentId));
    }

    @Test
    void 처리_이력_정리도_배치_크기_단위로_지운다() {
        for (var i = 0; i < 5; i++) processed(OLD.minus(i, ChronoUnit.MINUTES), GROUP);

        assertEquals(3, cleanup.deleteProcessedEvents(CUTOFF, 3));
        assertEquals(2, testProcessedCount());
        assertEquals(2, cleanup.deleteProcessedEvents(CUTOFF, 3));
        assertEquals(0, cleanup.deleteProcessedEvents(CUTOFF, 3));
    }

    @Test
    void 다른_연결이_잠금을_쥐고_있으면_지우지_않고_0을_돌려준다() throws Exception {
        outbox("SENT", OLD);
        processed(OLD, GROUP);

        try (var other = dataSource.getConnection()) {
            lock(other, RetentionCleanup.OUTBOX_LOCK_KEY);
            lock(other, RetentionCleanup.PROCESSED_EVENTS_LOCK_KEY);

            assertEquals(0, cleanup.deleteSentOutbox(CUTOFF, 1_000));
            assertEquals(0, cleanup.deleteProcessedEvents(CUTOFF, 1_000));
            assertEquals(1, count("outbox_events"));
            assertEquals(1, testProcessedCount());

            unlock(other, RetentionCleanup.OUTBOX_LOCK_KEY);
            unlock(other, RetentionCleanup.PROCESSED_EVENTS_LOCK_KEY);
        }

        // 잠금이 풀리면 지울 수 있다. 정리 쪽이 잠금을 남기지도 않는다
        assertEquals(1, cleanup.deleteSentOutbox(CUTOFF, 1_000));
        assertEquals(1, cleanup.deleteProcessedEvents(CUTOFF, 1_000));
    }

    @Test
    void 한_작업의_잠금은_다른_작업을_막지_않는다() throws Exception {
        outbox("SENT", OLD);
        processed(OLD, GROUP);

        try (var other = dataSource.getConnection()) {
            lock(other, RetentionCleanup.OUTBOX_LOCK_KEY);

            assertEquals(0, cleanup.deleteSentOutbox(CUTOFF, 1_000));
            assertEquals(1, cleanup.deleteProcessedEvents(CUTOFF, 1_000));

            unlock(other, RetentionCleanup.OUTBOX_LOCK_KEY);
        }
    }

    private static void lock(java.sql.Connection connection, long key) throws Exception {
        try (var statement = connection.prepareStatement("SELECT pg_advisory_lock(?)")) {
            statement.setLong(1, key);
            statement.execute();
        }
    }

    private static void unlock(java.sql.Connection connection, long key) throws Exception {
        try (var statement = connection.prepareStatement("SELECT pg_advisory_unlock(?)")) {
            statement.setLong(1, key);
            statement.execute();
        }
    }

    private void outbox(String status, Instant sentAt) {
        outbox(status, sentAt, sentAt == null ? OLD : sentAt, sentAt == null ? OLD : sentAt);
    }

    private void outbox(String status, Instant sentAt, Instant createdAt, Instant nextAttemptAt) {
        jdbc.update("""
                INSERT INTO outbox_events (event_id, topic, message_key, event_type, payload, status, attempts,
                                           next_attempt_at, created_at, sent_at)
                VALUES (?, 'payment.result', ?, 'PaymentApproved', '{}'::jsonb, ?, 0, ?, ?, ?)
                """, UUID.randomUUID(), "k-" + UUID.randomUUID(), status, Timestamp.from(nextAttemptAt),
                Timestamp.from(createdAt), sentAt == null ? null : Timestamp.from(sentAt));
    }

    private UUID processed(Instant processedAt, String group) {
        var eventId = UUID.randomUUID();
        processed(eventId, processedAt, group);
        return eventId;
    }

    private UUID processed(UUID eventId, Instant processedAt, String group) {
        jdbc.update("INSERT INTO processed_events (event_id, consumer_group, processed_at) VALUES (?, ?, ?)",
                eventId, group, Timestamp.from(processedAt));
        return eventId;
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    private int testProcessedCount() {
        return jdbc.queryForObject("SELECT count(*) FROM processed_events WHERE consumer_group LIKE 'test.%'", Integer.class);
    }

    private int processedCount(UUID eventId) {
        return jdbc.queryForObject("SELECT count(*) FROM processed_events WHERE event_id = ?", Integer.class, eventId);
    }
}
