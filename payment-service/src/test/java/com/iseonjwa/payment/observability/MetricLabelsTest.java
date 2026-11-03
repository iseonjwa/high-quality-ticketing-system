package com.iseonjwa.payment.observability;

import com.iseonjwa.payment.payment.application.ApprovePaymentCommand;
import com.iseonjwa.payment.payment.application.PaymentApprovalService;
import com.iseonjwa.payment.pg.application.UnknownCause;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

import static com.iseonjwa.payment.support.PaymentTokenFixtures.token;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 결제·환불·outbox·dead_letter 지표의 라벨에 고유 식별자가 없는지 실제 컨텍스트에서 확인한다(ADR 0024 결정 3). 지표를 모두
 * 등록시키려고 몇 가지 흐름(승인, DB 집계 갱신)을 돌리고 {@link PaymentMetrics}의 모든 메서드를 한 번씩 부른다.
 */
@SpringBootTest(properties = {"spring.config.import=", "spring.cloud.config.enabled=false", "eureka.client.enabled=false"})
@ActiveProfiles("test")
class MetricLabelsTest {
    private static final long RESERVATION = 9_100_001L;
    private static final List<String> FORBIDDEN = List.of("paymentid", "userid", "reservationid", "traceid", "eventid",
            "orderid", "refundid");
    private static final List<String> PREFIXES = List.of("payment.", "refund.", "outbox.", "dead.letter.");
    private static final Pattern UUID_LIKE = Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-.*");
    private static final Pattern NUMERIC = Pattern.compile("\\d{4,}");

    @Autowired
    MeterRegistry meters;
    @Autowired
    PaymentMetrics metrics;
    @Autowired
    PaymentApprovalService approvals;
    @Autowired
    DbAggregateGauges gauges;
    @Autowired
    JdbcTemplate jdbc;

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM idempotency_records WHERE resource_id IN (SELECT id FROM payments WHERE reservation_id = ?)",
                RESERVATION);
        jdbc.update("DELETE FROM outbox_events WHERE message_key = ?", String.valueOf(RESERVATION));
        jdbc.update("DELETE FROM payment_status_histories WHERE payment_id IN (SELECT id FROM payments WHERE reservation_id = ?)",
                RESERVATION);
        jdbc.update("DELETE FROM payments WHERE reservation_id = ?", RESERVATION);
    }

    @Test
    void 라벨_키에_고유_식별자가_없고_값도_식별자처럼_생기지_않았다() {
        touchEveryMetric();
        // 승인 흐름. PG 서버가 없든 있든 결제가 만들어지고 승인 지표가 기록된다
        approvals.approve(new ApprovePaymentCommand(42L, UUID.randomUUID(), token(42L, RESERVATION, 50000).build(), 50000));
        gauges.refresh();

        var inspected = new ArrayList<String>();
        for (Meter meter : meters.getMeters()) {
            var name = meter.getId().getName();
            if (PREFIXES.stream().noneMatch(name::startsWith) && !name.startsWith("resilience4j.")) continue;
            inspected.add(name);
            for (var tag : meter.getId().getTags()) {
                var normalized = tag.getKey().replace("_", "").replace("-", "").replace(".", "").toLowerCase(Locale.ROOT);
                assertFalse(FORBIDDEN.contains(normalized), name + " 라벨 키 " + tag.getKey());
                assertFalse(UUID_LIKE.matcher(tag.getValue()).matches(), name + " 라벨 값 " + tag.getValue());
                assertFalse(NUMERIC.matcher(tag.getValue()).matches(), name + " 라벨 값 " + tag.getValue());
            }
        }
        assertTrue(inspected.contains("payment.approve"), "검사 대상에 승인 지표가 있어야 한다: " + inspected);
        assertTrue(inspected.contains("payment.pg.call"), "검사 대상에 PG 호출 지표가 있어야 한다: " + inspected);
        assertTrue(inspected.contains("payment.manual.check.count"), "검사 대상에 DB 집계 Gauge가 있어야 한다: " + inspected);
    }

    private void touchEveryMetric() {
        for (var outcome : ApproveOutcome.values()) metrics.recordApprove(outcome, 1_000_000);
        for (var operation : PgOperation.values()) {
            for (var outcome : PgCallOutcome.values()) metrics.recordPgCall(operation, outcome, 1_000_000);
            for (var cause : UnknownCause.values()) metrics.pgUnknown(operation, cause);
            metrics.pgAmountMismatch(operation);
        }
        for (var operation : TransitionOperation.values()) metrics.transitionConflict(operation);
        metrics.dlqHandlerFailure("payment.command.dlq");
        metrics.trackJob("recheck", java.time.Instant.now());
        metrics.jobFailed("recheck");
    }
}
