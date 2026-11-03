package com.iseonjwa.payment.observability;

import com.iseonjwa.payment.support.AppendOnlyCleanup;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * DB 집계 Gauge를 PostgreSQL에서 확인한다(ISE-156). 테스트는 트랜잭션 안에서 다른 테스트가 남긴 행까지 지운 뒤 상태별 행을 넣고,
 * 끝나면 롤백한다. 그래서 건수와 최고령을 정확한 값으로 단언할 수 있다.
 */
@SpringBootTest(properties = {"spring.config.import=", "spring.cloud.config.enabled=false", "eureka.client.enabled=false"})
@ActiveProfiles("test")
@Transactional
class DbAggregateGaugesTest {
    private static final Instant NOW = Instant.parse("2026-11-03T00:00:00Z");
    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    @Autowired
    JdbcTemplate jdbc;

    private final MeterRegistry meters = new SimpleMeterRegistry();
    private DbAggregateGauges gauges;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM dead_letters");
        jdbc.update("DELETE FROM refunds");
        AppendOnlyCleanup.deleteAllHistories(jdbc);
        jdbc.update("DELETE FROM idempotency_records");
        jdbc.update("DELETE FROM outbox_events");
        jdbc.update("DELETE FROM payments");
        gauges = new DbAggregateGauges(meters, jdbc, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void 행이_없으면_모든_Gauge가_0이다() {
        gauges.refresh();

        for (var name : new String[] {"payment.requested.oldest.seconds", "payment.unknown.count",
                "payment.unknown.oldest.seconds", "payment.manual.check.count", "payment.manual.check.oldest.seconds",
                "refund.pending.count", "refund.pending.oldest.seconds", "refund.manual.check.count",
                "refund.manual.check.oldest.seconds", "outbox.pending.oldest.seconds", "outbox.failed.count",
                "outbox.retrying.count"}) {
            assertEquals(0, value(name), name);
        }
        assertNull(meters.find("dead.letter.open.count").gauge(), "토픽을 본 적이 없으면 시계열이 없다");
    }

    @Test
    void 결제는_상태별_건수와_최고령을_센다_수동_확인은_들어간_시각_기준이다() {
        payment("REQUESTED", 100, 100);
        payment("REQUESTED", 30, 30);
        payment("UNKNOWN", 300, 10);
        payment("UNKNOWN", 200, 10);
        // 오래전에 만들어졌지만 50초 전에 수동 확인으로 들어갔다
        payment("NEEDS_MANUAL_CHECK", 9000, 50);
        payment("NEEDS_MANUAL_CHECK", 9000, 20);
        // 미확정이 아닌 행은 오래돼도 세지 않는다
        payment("DECLINED", 99999, 99999);

        gauges.refresh();

        assertEquals(100, value("payment.requested.oldest.seconds"));
        assertEquals(2, value("payment.unknown.count"));
        assertEquals(300, value("payment.unknown.oldest.seconds"));
        assertEquals(2, value("payment.manual.check.count"));
        assertEquals(50, value("payment.manual.check.oldest.seconds"));
    }

    @Test
    void 환불은_대기와_수동_확인을_따로_센다() {
        refund("REQUESTED", 400, 400);
        refund("UNKNOWN", 600, 100);
        refund("NEEDS_MANUAL_CHECK", 9000, 70);
        refund("REFUNDED", 99999, 99999);
        refund("FAILED", 99999, 99999);

        gauges.refresh();

        assertEquals(2, value("refund.pending.count"));
        assertEquals(600, value("refund.pending.oldest.seconds"));
        assertEquals(1, value("refund.manual.check.count"));
        assertEquals(70, value("refund.manual.check.oldest.seconds"));
        assertEquals(0, value("payment.manual.check.count"), "결제와 환불 지표가 섞이지 않는다");
    }

    @Test
    void Outbox는_대기_최고령과_재시도_중_FAILED_건수를_센다() {
        outbox("PENDING", 0, 500);
        outbox("PENDING", 2, 800);
        outbox("PENDING", 1, 100);
        outbox("FAILED", 5, 9000);
        outbox("FAILED", 1, 9000);
        outbox("SENT", 3, 99999);

        gauges.refresh();

        assertEquals(800, value("outbox.pending.oldest.seconds"));
        assertEquals(2, value("outbox.retrying.count"));
        assertEquals(2, value("outbox.failed.count"));
    }

    @Test
    void dead_letters는_OPEN만_원본_토픽별로_센다() {
        deadLetter("payment.command", "OPEN", 1000);
        deadLetter("payment.command", "OPEN", 200);
        deadLetter("payment.command", "RESOLVED", 99999);
        deadLetter("reservation.result", "OPEN", 60);
        System.gc();

        gauges.refresh();
        System.gc();

        assertEquals(2, topicValue("dead.letter.open.count", "payment.command"));
        assertEquals(1000, topicValue("dead.letter.open.oldest.seconds", "payment.command"));
        assertEquals(1, topicValue("dead.letter.open.count", "reservation.result"));
        assertEquals(60, topicValue("dead.letter.open.oldest.seconds", "reservation.result"));
    }

    @Test
    void dead_letters_Gauge는_Prometheus에서_토픽_라벨을_가진_이름으로_나온다() {
        deadLetter("payment.command", "OPEN", 1000);
        var prometheus = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        new DbAggregateGauges(prometheus, jdbc, Clock.fixed(NOW, ZoneOffset.UTC)).refresh();

        var scrape = prometheus.scrape();

        assertTrue(scrape.contains("dead_letter_open_count{topic=\"payment.command\"} 1.0"), scrape);
        assertTrue(scrape.contains("dead_letter_open_oldest_seconds{topic=\"payment.command\"} 1000.0"), scrape);
    }

    @Test
    void 해결돼도_한_번_본_토픽은_0으로_남는다() {
        deadLetter("payment.command", "OPEN", 1000);
        gauges.refresh();
        jdbc.update("UPDATE dead_letters SET status = 'RESOLVED', resolution = 'DUPLICATE', resolved_at = now()");

        gauges.refresh();

        assertEquals(0, topicValue("dead.letter.open.count", "payment.command"));
        assertEquals(0, topicValue("dead.letter.open.oldest.seconds", "payment.command"));
    }

    @Test
    void 갱신이_실패하면_예외가_나가고_이전_값이_그대로_남는다() {
        payment("UNKNOWN", 300, 10);
        outbox("FAILED", 5, 100);
        var flaky = spy(jdbc);
        var flakyMeters = new SimpleMeterRegistry();
        var failing = new DbAggregateGauges(flakyMeters, flaky, Clock.fixed(NOW, ZoneOffset.UTC));
        failing.refresh();
        assertEquals(1, value(flakyMeters, "payment.unknown.count"));
        // 그 사이 DB가 바뀌었지만 마지막 쿼리가 실패한다. 앞선 쿼리가 새 값을 읽었어도 일부만 반영되면 안 된다
        payment("UNKNOWN", 400, 10);
        outbox("FAILED", 6, 100);
        doThrow(new DataAccessResourceFailureException("DB 연결 실패")).when(flaky)
                .query(anyString(), any(RowCallbackHandler.class));

        assertThrows(DataAccessResourceFailureException.class, failing::refresh);

        assertEquals(1, value(flakyMeters, "payment.unknown.count"));
        assertEquals(300, value(flakyMeters, "payment.unknown.oldest.seconds"));
        assertEquals(1, value(flakyMeters, "outbox.failed.count"));
    }

    @Test
    void 수집은_DB를_조회하지_않는다() {
        payment("UNKNOWN", 300, 10);
        var counting = spy(jdbc);
        var localMeters = new SimpleMeterRegistry();
        var local = new DbAggregateGauges(localMeters, counting, Clock.fixed(NOW, ZoneOffset.UTC));
        local.refresh();
        clearInvocations(counting);

        for (var i = 0; i < 5; i++) {
            localMeters.getMeters().forEach(meter -> meter.measure().forEach(measurement -> measurement.getValue()));
        }

        verifyNoInteractions(counting);
    }

    private double value(String name) {
        return value(meters, name);
    }

    private static double value(MeterRegistry registry, String name) {
        return registry.get(name).gauge().value();
    }

    private double topicValue(String name, String topic) {
        return meters.get(name).tag("topic", topic).gauge().value();
    }

    private long payment(String status, int createdAgoSeconds, int updatedAgoSeconds) {
        var sequence = SEQUENCE.incrementAndGet();
        var needsSchedule = status.equals("REQUESTED") || status.equals("UNKNOWN");
        return jdbc.queryForObject("""
                INSERT INTO payments (payment_no, reservation_id, user_id, amount, method, status, pg_order_id,
                    decline_code, next_check_at, created_at, updated_at)
                VALUES (?, ?, 1, 50000, 'CARD', ?, ?, ?, ?, ?, ?) RETURNING id
                """, Long.class, "PAY-G" + sequence, 9_000_000L + sequence, status, UUID.randomUUID(),
                status.equals("DECLINED") ? "OTHER" : null, needsSchedule ? Timestamp.from(NOW) : null,
                ago(createdAgoSeconds), ago(updatedAgoSeconds));
    }

    private void refund(String status, int createdAgoSeconds, int updatedAgoSeconds) {
        var paymentId = payment("DECLINED", 1, 1);
        var needsSchedule = status.equals("REQUESTED") || status.equals("UNKNOWN");
        jdbc.update("""
                INSERT INTO refunds (payment_id, pg_refund_id, amount, reason, source, status, next_check_at,
                    refunded_at, created_at, updated_at)
                VALUES (?, ?, 50000, 'CANCELED', 'EVENT', ?, ?, ?, ?, ?)
                """, paymentId, UUID.randomUUID(), status, needsSchedule ? Timestamp.from(NOW) : null,
                status.equals("REFUNDED") ? Timestamp.from(NOW) : null, ago(createdAgoSeconds), ago(updatedAgoSeconds));
    }

    private void outbox(String status, int attempts, int createdAgoSeconds) {
        jdbc.update("""
                INSERT INTO outbox_events (event_id, topic, message_key, event_type, payload, status, attempts,
                    next_attempt_at, created_at)
                VALUES (?, 'payment.result', ?, 'PaymentApproved', '{}'::jsonb, ?, ?, ?, ?)
                """, UUID.randomUUID(), "g" + SEQUENCE.incrementAndGet(), status, attempts, Timestamp.from(NOW),
                ago(createdAgoSeconds));
    }

    private void deadLetter(String topic, String status, int createdAgoSeconds) {
        var resolved = status.equals("RESOLVED");
        jdbc.update("""
                INSERT INTO dead_letters (source_topic, event_key, payload_raw, reason, status, resolution, resolved_at,
                    created_at)
                VALUES (?, ?, '{}', '사유', ?, ?, ?, ?)
                """, topic, "key-" + SEQUENCE.incrementAndGet(), status, resolved ? "DUPLICATE" : null,
                resolved ? Timestamp.from(NOW) : null, ago(createdAgoSeconds));
    }

    private static Timestamp ago(int seconds) {
        return Timestamp.from(NOW.minus(Duration.ofSeconds(seconds)));
    }
}
