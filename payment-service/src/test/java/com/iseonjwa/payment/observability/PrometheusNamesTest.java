package com.iseonjwa.payment.observability;

import com.iseonjwa.common.messaging.outbox.OutboxMetrics;
import com.iseonjwa.payment.pg.application.UnknownCause;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.micrometer.tagged.TaggedBulkheadMetrics;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Prometheus로 내보낸 실제 이름을 확인한다(observability.md 1절). Micrometer 이름이 예약 접미사 때문에 바뀌거나 등록이 거부되면
 * 여기서 드러난다. 대시보드와 알림 규칙(ISE-157)이 이 이름을 쓴다.
 */
class PrometheusNamesTest {
    // 시계열 이름 앞에 오는 줄 형태: "<이름>{" 또는 "<이름> "
    private static final List<String> EXPECTED = List.of(
            "payment_approve_seconds_count", "payment_approve_seconds_sum", "payment_approve_seconds_bucket",
            "payment_pg_call_seconds_count", "payment_pg_call_seconds_sum",
            "payment_pg_unknown_total", "payment_pg_amount_mismatch_total", "payment_transition_conflict_total",
            "payment_dlq_handler_failures_total", "payment_job_last_success_timestamp", "payment_job_failures_total",
            "outbox_relay_last_success_timestamp", "outbox_relay_failures_total", "outbox_events_failed_total",
            "payment_requested_oldest_seconds", "payment_unknown_count", "payment_unknown_oldest_seconds",
            "payment_manual_check_count", "payment_manual_check_oldest_seconds", "refund_pending_count",
            "refund_pending_oldest_seconds", "refund_manual_check_count", "refund_manual_check_oldest_seconds",
            "outbox_pending_oldest_seconds", "outbox_failed_count", "outbox_retrying_count",
            "resilience4j_circuitbreaker_state", "resilience4j_bulkhead_available_concurrent_calls");

    @Test
    void 목표_이름이_모두_Prometheus_출력에_있다() {
        var prometheus = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        var clock = Clock.systemUTC();
        var metrics = new PaymentMetrics(prometheus);
        metrics.recordApprove(ApproveOutcome.APPROVED, 1_000_000);
        metrics.recordPgCall(PgOperation.APPROVE, PgCallOutcome.APPROVED, 1_000_000);
        metrics.pgUnknown(PgOperation.APPROVE, UnknownCause.TIMEOUT);
        metrics.trackDlqHandler("payment.command.dlq");
        metrics.trackJob("recheck", Instant.now());
        new OutboxMetrics(prometheus, clock);
        new DbAggregateGauges(prometheus, mock(JdbcTemplate.class), clock);
        var breakers = CircuitBreakerRegistry.ofDefaults();
        var bulkheads = BulkheadRegistry.ofDefaults();
        breakers.circuitBreaker("pg-approve");
        bulkheads.bulkhead("pg-approve");
        TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(breakers).bindTo(prometheus);
        TaggedBulkheadMetrics.ofBulkheadRegistry(bulkheads).bindTo(prometheus);

        var scrape = prometheus.scrape();

        for (var name : EXPECTED) {
            assertTrue(scrape.lines().anyMatch(line -> line.startsWith(name + "{") || line.startsWith(name + " ")),
                    name + " 이(가) 없다\n" + names(scrape));
        }
    }

    private static String names(String scrape) {
        return String.join("\n", scrape.lines().filter(line -> !line.startsWith("#")).map(line -> line.split("[{ ]")[0])
                .distinct().toList());
    }
}
