package com.iseonjwa.payment.observability;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MultiGauge;
import io.micrometer.core.instrument.Tags;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.ToDoubleFunction;

/**
 * 돈이 확정되지 않은 채 남은 건과 그 나이를 DB에서 집계해 Gauge로 보인다(observability.md 1절, ADR 0024 결정 3).
 * {@link #refresh}가 테이블마다 쿼리 하나로 읽어 메모리 스냅숏을 바꾸고, Gauge는 그 스냅숏만 읽는다. 수집(스크레이프) 때는
 * DB를 조회하지 않는다. {@code housekeeping} 실행기의 {@code gauge-refresh} 작업이 주기적으로 부른다.
 *
 * <p>Note: 나이는 갱신 시각의 시계에서 기준 시각을 뺀 초다. 해당 행이 없으면 0이다. 기준 시각은 돈이 확정되지 않은 채 머문 시간을
 * 보려는 상태({@code REQUESTED}·{@code UNKNOWN}·환불 대기·Outbox {@code PENDING}·{@code dead_letters OPEN})는 {@code created_at},
 * 수동 확인으로 들어간 뒤의 시간을 보려는 {@code NEEDS_MANUAL_CHECK}는 {@code updated_at}이다. NMC 행은 그 뒤 코드가 고치지
 * 않아 {@code updated_at}이 들어간 시각 그대로다(Ops 명령이 NMC 행을 제자리에서 고치게 되면 이 전제를 다시 확인한다).
 *
 * <p>Note: 갱신이 실패하면(DB 오류) 스냅숏을 바꾸지 않고 예외를 그대로 던진다. 이전 값이 남고 작업 실패 지표가 오른다. 모든 쿼리를
 * 읽은 뒤에 한꺼번에 바꾸므로 일부 테이블만 새 값이 되는 일은 없다. 토픽별 {@code dead_letters}는 한 번 본 토픽을 0으로 계속
 * 보여 시계열이 사라지지 않게 한다.
 */
public class DbAggregateGauges {
    private static final String UNKNOWN_TOPIC = "unknown";

    // 상태가 WHERE에 있어 부분 인덱스(idx_payments_unsettled_created_at)를 탄다. NMC의 나이만 updated_at이라 그 행만 테이블을 읽는다
    private static final String PAYMENTS = """
            SELECT min(created_at) FILTER (WHERE status = 'REQUESTED') AS requested_oldest,
                   count(*) FILTER (WHERE status = 'UNKNOWN') AS unknown_count,
                   min(created_at) FILTER (WHERE status = 'UNKNOWN') AS unknown_oldest,
                   count(*) FILTER (WHERE status = 'NEEDS_MANUAL_CHECK') AS manual_count,
                   min(updated_at) FILTER (WHERE status = 'NEEDS_MANUAL_CHECK') AS manual_oldest
            FROM payments
            WHERE status IN ('REQUESTED', 'UNKNOWN', 'NEEDS_MANUAL_CHECK')
            """;
    private static final String REFUNDS = """
            SELECT count(*) FILTER (WHERE status IN ('REQUESTED', 'UNKNOWN')) AS pending_count,
                   min(created_at) FILTER (WHERE status IN ('REQUESTED', 'UNKNOWN')) AS pending_oldest,
                   count(*) FILTER (WHERE status = 'NEEDS_MANUAL_CHECK') AS manual_count,
                   min(updated_at) FILTER (WHERE status = 'NEEDS_MANUAL_CHECK') AS manual_oldest
            FROM refunds
            WHERE status IN ('REQUESTED', 'UNKNOWN', 'NEEDS_MANUAL_CHECK')
            """;
    // Note: 조건을 IN이 아니라 OR로 쓴다. IN은 어느 부분 인덱스에도 맞지 않아 SENT가 쌓인 테이블 전체를 순차로 읽고, OR은
    // PENDING·FAILED 부분 인덱스(idx_outbox_events_pending_*, idx_outbox_events_failed_message_key)의 BitmapOr로 두 상태의 행만 읽는다
    private static final String OUTBOX = """
            SELECT min(created_at) FILTER (WHERE status = 'PENDING') AS pending_oldest,
                   count(*) FILTER (WHERE status = 'PENDING' AND attempts > 0) AS retrying_count,
                   count(*) FILTER (WHERE status = 'FAILED') AS failed_count
            FROM outbox_events
            WHERE status = 'PENDING' OR status = 'FAILED'
            """;
    private static final String DEAD_LETTERS = """
            SELECT source_topic, count(*) AS open_count, min(created_at) AS oldest
            FROM dead_letters
            WHERE status = 'OPEN'
            GROUP BY source_topic
            """;

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final MultiGauge deadLetterCount;
    private final MultiGauge deadLetterOldest;
    private final Map<String, Boolean> knownTopics = new ConcurrentHashMap<>();
    private volatile Snapshot snapshot = Snapshot.EMPTY;

    public DbAggregateGauges(MeterRegistry registry, JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
        gauge(registry, "payment.requested.oldest.seconds", "REQUESTED 결제의 최고령(초). 재확인이 집지 못한 결제를 본다",
                s -> s.requestedOldest);
        gauge(registry, "payment.unknown.count", "UNKNOWN 결제 건수", s -> s.unknownCount);
        gauge(registry, "payment.unknown.oldest.seconds", "UNKNOWN 결제의 최고령(초)", s -> s.unknownOldest);
        gauge(registry, "payment.manual.check.count", "NEEDS_MANUAL_CHECK 결제 건수", s -> s.paymentManualCount);
        gauge(registry, "payment.manual.check.oldest.seconds", "NEEDS_MANUAL_CHECK 결제의 최고령(초, 들어간 시각 기준)",
                s -> s.paymentManualOldest);
        gauge(registry, "refund.pending.count", "대기 중(REQUESTED·UNKNOWN) 환불 건수", s -> s.refundPendingCount);
        gauge(registry, "refund.pending.oldest.seconds", "대기 중 환불의 최고령(초)", s -> s.refundPendingOldest);
        gauge(registry, "refund.manual.check.count", "NEEDS_MANUAL_CHECK 환불 건수", s -> s.refundManualCount);
        gauge(registry, "refund.manual.check.oldest.seconds", "NEEDS_MANUAL_CHECK 환불의 최고령(초, 들어간 시각 기준)",
                s -> s.refundManualOldest);
        gauge(registry, "outbox.pending.oldest.seconds", "PENDING Outbox 이벤트의 최고령(초). 브로커 장애와 릴레이 정체를 본다",
                s -> s.outboxPendingOldest);
        gauge(registry, "outbox.failed.count", "FAILED Outbox 이벤트 건수", s -> s.outboxFailedCount);
        gauge(registry, "outbox.retrying.count", "재시도 중(PENDING이면서 시도 1회 이상) Outbox 이벤트 건수",
                s -> s.outboxRetryingCount);
        this.deadLetterCount = MultiGauge.builder("dead.letter.open.count")
                .description("OPEN dead_letters 건수(원본 토픽별)").register(registry);
        this.deadLetterOldest = MultiGauge.builder("dead.letter.open.oldest.seconds")
                .description("OPEN dead_letters의 최고령(초, 원본 토픽별)").register(registry);
    }

    /**
     * 집계해 스냅숏을 바꾼다. 쿼리가 하나라도 실패하면 예외가 나가고 이전 값이 그대로 남는다.
     */
    public void refresh() {
        var now = clock.instant();
        var payments = jdbc.queryForObject(PAYMENTS, (rs, row) -> new Payments(
                age(rs, "requested_oldest", now), rs.getLong("unknown_count"), age(rs, "unknown_oldest", now),
                rs.getLong("manual_count"), age(rs, "manual_oldest", now)));
        var refunds = jdbc.queryForObject(REFUNDS, (rs, row) -> new Refunds(
                rs.getLong("pending_count"), age(rs, "pending_oldest", now), rs.getLong("manual_count"),
                age(rs, "manual_oldest", now)));
        var outbox = jdbc.queryForObject(OUTBOX, (rs, row) -> new Outbox(
                age(rs, "pending_oldest", now), rs.getLong("retrying_count"), rs.getLong("failed_count")));
        var topics = new TreeMap<String, TopicStat>();
        jdbc.query(DEAD_LETTERS, rs -> {
            var topic = rs.getString("source_topic");
            topics.put(topic == null ? UNKNOWN_TOPIC : topic,
                    new TopicStat(rs.getLong("open_count"), age(rs, "oldest", now)));
        });

        // 쿼리가 모두 성공한 뒤에만 바꾼다
        snapshot = new Snapshot(payments.requestedOldest, payments.unknownCount, payments.unknownOldest,
                payments.manualCount, payments.manualOldest, refunds.pendingCount, refunds.pendingOldest,
                refunds.manualCount, refunds.manualOldest, outbox.pendingOldest, outbox.failedCount,
                outbox.retryingCount);
        publishTopics(topics);
    }

    private void publishTopics(Map<String, TopicStat> open) {
        open.keySet().forEach(topic -> knownTopics.put(topic, Boolean.TRUE));
        var counts = new ArrayList<MultiGauge.Row<?>>();
        var ages = new ArrayList<MultiGauge.Row<?>>();
        for (var topic : new TreeMap<>(knownTopics).keySet()) {
            var stat = open.getOrDefault(topic, TopicStat.NONE);
            counts.add(MultiGauge.Row.of(Tags.of("topic", topic), stat.count, Long::doubleValue));
            ages.add(MultiGauge.Row.of(Tags.of("topic", topic), stat.oldest, Double::doubleValue));
        }
        deadLetterCount.register(counts, true);
        deadLetterOldest.register(ages, true);
    }

    private void gauge(MeterRegistry registry, String name, String description, ToDoubleFunction<Snapshot> value) {
        Gauge.builder(name, this, gauges -> value.applyAsDouble(gauges.snapshot)).description(description)
                .register(registry);
    }

    // 기준 시각이 없으면(해당 행이 없다) 0이다. 시계 오차로 음수가 되면 0으로 자른다
    private static double age(ResultSet rs, String column, Instant now) throws SQLException {
        var base = rs.getTimestamp(column);
        if (base == null) return 0;
        return Math.max(0, Duration.between(base.toInstant(), now).toMillis() / 1000.0);
    }

    private record Payments(double requestedOldest, long unknownCount, double unknownOldest, long manualCount,
            double manualOldest) {}

    private record Refunds(long pendingCount, double pendingOldest, long manualCount, double manualOldest) {}

    private record Outbox(double pendingOldest, long retryingCount, long failedCount) {}

    private record TopicStat(long count, double oldest) {
        static final TopicStat NONE = new TopicStat(0, 0);
    }

    private record Snapshot(double requestedOldest, double unknownCount, double unknownOldest,
            double paymentManualCount, double paymentManualOldest, double refundPendingCount,
            double refundPendingOldest, double refundManualCount, double refundManualOldest,
            double outboxPendingOldest, double outboxFailedCount, double outboxRetryingCount) {
        static final Snapshot EMPTY = new Snapshot(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    }
}
