package com.iseonjwa.payment.infrastructure.housekeeping;

import com.iseonjwa.common.messaging.retention.RetentionCleanup;
import com.iseonjwa.payment.support.RefundFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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

// Note: 결제 행이 필요해 RefundFixtures(8,800,000번대 예매 ID)를 쓴다. 멱등 기록은 user_id 7777로만 만들고 지운다.
@SpringBootTest(properties = {"spring.config.import=", "spring.cloud.config.enabled=false", "eureka.client.enabled=false"})
@ActiveProfiles("test")
class IdempotencyRecordCleanupTest {
    private static final long USER_ID = 7777L;
    private static final Instant NOW = Instant.parse("2026-11-10T00:00:00Z");
    private static final Instant EXPIRED = NOW.minus(1, ChronoUnit.HOURS);
    private static final Instant ALIVE = NOW.plus(1, ChronoUnit.HOURS);

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    PlatformTransactionManager transactionManager;
    @Autowired
    DataSource dataSource;

    private RefundFixtures fixtures;
    private IdempotencyRecordCleanup cleanup;

    @BeforeEach
    void setUp() {
        fixtures = new RefundFixtures(jdbc);
        cleanup = new IdempotencyRecordCleanup(new RetentionCleanup(jdbc, transactionManager));
        clean();
    }

    @AfterEach
    void tearDown() {
        clean();
    }

    private void clean() {
        jdbc.update("DELETE FROM idempotency_records WHERE user_id = ?", USER_ID);
        fixtures.cleanUp(java.util.List.of());
    }

    @ParameterizedTest
    @ValueSource(strings = {"APPROVED", "DECLINED"})
    void 확정된_결제에_연결된_만료_기록은_ORIGIN과_ALIAS_모두_지운다(String status) {
        var payment = fixtures.payment(status);
        record(payment.paymentId(), "ORIGIN", EXPIRED);
        record(payment.paymentId(), "ALIAS", EXPIRED);

        assertEquals(2, cleanup.deleteExpired(NOW, 1_000));
        assertEquals(0, count());
    }

    @ParameterizedTest
    @ValueSource(strings = {"REQUESTED", "UNKNOWN", "NEEDS_MANUAL_CHECK"})
    void 미확정_결제에_연결된_만료_기록은_ORIGIN과_ALIAS_모두_남긴다(String status) {
        var payment = fixtures.payment(status);
        record(payment.paymentId(), "ORIGIN", EXPIRED);
        record(payment.paymentId(), "ALIAS", EXPIRED);

        assertEquals(0, cleanup.deleteExpired(NOW, 1_000));
        assertEquals(2, count());
    }

    @Test
    void 만료되지_않은_기록은_확정된_결제여도_남긴다() {
        var payment = fixtures.payment("APPROVED");
        record(payment.paymentId(), "ORIGIN", ALIVE);

        assertEquals(0, cleanup.deleteExpired(NOW, 1_000));
        assertEquals(1, count());
    }

    @Test
    void 남겨야_하는_기록이_배치_크기만큼_앞에_있어도_뒤의_지울_기록에_닿는다() {
        var unsettled = fixtures.payment("UNKNOWN");
        var settled = fixtures.payment("APPROVED");
        for (var i = 0; i < 3; i++) record(unsettled.paymentId(), "ALIAS", EXPIRED.minus(1 + i, ChronoUnit.DAYS));
        record(settled.paymentId(), "ORIGIN", EXPIRED);

        assertEquals(1, cleanup.deleteExpired(NOW, 2));
        assertEquals(3, count());
    }

    @Test
    void 배치_크기_단위로_지운다() {
        var payment = fixtures.payment("APPROVED");
        for (var i = 0; i < 5; i++) record(payment.paymentId(), "ALIAS", EXPIRED.minus(i, ChronoUnit.MINUTES));

        assertEquals(2, cleanup.deleteExpired(NOW, 2));
        assertEquals(3, count());
        assertEquals(2, cleanup.deleteExpired(NOW, 2));
        assertEquals(1, cleanup.deleteExpired(NOW, 2));
        assertEquals(0, cleanup.deleteExpired(NOW, 2));
    }

    @Test
    void 다른_연결이_잠금을_쥐고_있으면_지우지_않고_0을_돌려준다() throws Exception {
        var payment = fixtures.payment("APPROVED");
        record(payment.paymentId(), "ORIGIN", EXPIRED);

        try (var other = dataSource.getConnection(); var statement = other.prepareStatement("SELECT pg_advisory_lock(?)")) {
            statement.setLong(1, IdempotencyRecordCleanup.LOCK_KEY);
            statement.execute();

            assertEquals(0, cleanup.deleteExpired(NOW, 1_000));
            assertEquals(1, count());
        }
    }

    private void record(long paymentId, String linkType, Instant expiresAt) {
        jdbc.update("""
                INSERT INTO idempotency_records (user_id, idem_key, request_hash, resource_id, link_type, expires_at, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, USER_ID, UUID.randomUUID(), "0".repeat(64), paymentId, linkType, Timestamp.from(expiresAt),
                Timestamp.from(NOW.minus(2, ChronoUnit.DAYS)));
    }

    private int count() {
        return jdbc.queryForObject("SELECT count(*) FROM idempotency_records WHERE user_id = ?", Integer.class, USER_ID);
    }
}
