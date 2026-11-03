package com.iseonjwa.payment.scenario;

import com.iseonjwa.payment.observability.DbAggregateGauges;
import com.iseonjwa.payment.pg.infrastructure.MockPgLedgerClient;
import com.iseonjwa.payment.reconciliation.application.ReconciliationService;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 장애 시나리오가 끝날 때마다 돌리는 검증기(failure-scenarios.md 4절). 하나라도 어긋나면 위반 항목을 모아 한 번에 보여 준다.
 * 범위는 이 시나리오가 만든 예매 ID 구간이고, Mock PG 원장은 시나리오 시작 때 비운 것이라 전부 이 시나리오의 기록이다.
 *
 * <ul>
 *   <li>V1 승인 중복 없음: 예매당 결제 ≤ 1, 주문당 PG 승인 호출({@code approveCalls}) ≤ 1, PG에만 있는 승인 0.</li>
 *   <li>V2 환불 중복 없음: 결제당 환불 행 ≤ 1, PG 환불 수 ≤ 1, PG 환불 합계 ≤ 승인 금액.</li>
 *   <li>V3(결제 쪽): 모든 결제가 정상 종결이거나 명시적 미해결이고, 미해결이면 해당 Gauge가 0보다 크다.</li>
 * </ul>
 *
 * <p>Note: V3는 결제 쪽만 본다. 결제↔예매 상태 대조와 대사 중 RC-7·RC-8은 `ReconciliationScenarioTest`가, 예매와 대조하는 RC-1~RC-6은 `ReservationRulesTest`와 `ReconciliationReservationScenarioTest`(N23)가 가짜 예매 서버로 맡는다. 이 검증기에는 예매 쪽 확인이 없다.
 *
 * <p>Note: "명시적 미해결"은 {@code UNKNOWN}·{@code NEEDS_MANUAL_CHECK} 결제, 대기({@code REQUESTED}·{@code UNKNOWN})·
 * {@code NEEDS_MANUAL_CHECK} 환불, {@code OPEN} {@code dead_letters}다. {@code REQUESTED} 결제는 재확인이 집어 가야 하므로
 * 시나리오 끝에 남아 있으면 위반이다. Gauge는 DB 전체를 센다. 그래서 "0보다 크다"는 이 시나리오의 미해결 행이 집계에서
 * 빠지지 않았다는 하한 확인이다.
 */
final class ScenarioInvariants {
    private static final Duration IN_FLIGHT_TIMEOUT = Duration.ofSeconds(5);
    private static final Set<String> SETTLED_PAYMENT = Set.of("APPROVED", "DECLINED", "REFUNDED");
    private static final Set<String> UNRESOLVED_PAYMENT = Set.of("UNKNOWN", "NEEDS_MANUAL_CHECK");

    private final JdbcTemplate jdbc;
    private final MockPgLedgerClient ledger;
    private final DbAggregateGauges gauges;
    private final MeterRegistry meters;
    private final ReconciliationService reconciliation;
    private final long firstReservation;
    private final long lastReservation;
    private final long deadLetterPartition;

    ScenarioInvariants(JdbcTemplate jdbc, MockPgLedgerClient ledger, DbAggregateGauges gauges, MeterRegistry meters,
            ReconciliationService reconciliation, long firstReservation, long lastReservation, long deadLetterPartition) {
        this.jdbc = jdbc;
        this.ledger = ledger;
        this.gauges = gauges;
        this.meters = meters;
        this.reconciliation = reconciliation;
        this.firstReservation = firstReservation;
        this.lastReservation = lastReservation;
        this.deadLetterPartition = deadLetterPartition;
    }

    /** V1~V3를 모두 실행하고 하나라도 어긋나면 위반 전부를 담아 실패한다. */
    void assertHolds() {
        // 처리 중인 승인이 남아 있으면 PG 쪽 목록이 덜 보일 수 있다. 끝난 뒤에 대조한다
        ledger.awaitNoInFlight(IN_FLIGHT_TIMEOUT);
        var all = new ArrayList<String>();
        all.addAll(prefixed("V1", v1()));
        all.addAll(prefixed("V2", v2()));
        all.addAll(prefixed("V3", v3()));
        all.addAll(prefixed("V3", v3Reconciliation()));
        if (!all.isEmpty()) {
            throw new AssertionError("장애 시나리오 불변 조건 위반 " + all.size() + "건:\n - " + String.join("\n - ", all));
        }
    }

    /** V1 승인 중복 없음. */
    List<String> v1() {
        var violations = new ArrayList<String>();
        for (var duplicated : jdbc.queryForList("""
                SELECT reservation_id, count(*) AS payment_count FROM payments
                WHERE reservation_id BETWEEN ? AND ? GROUP BY reservation_id HAVING count(*) > 1""",
                firstReservation, lastReservation)) {
            violations.add("예매 " + duplicated.get("reservation_id") + "의 결제가 " + duplicated.get("payment_count") + "건이다");
        }
        var payments = payments();
        for (var payment : payments) {
            ledger.payment(payment.pgOrderId().toString()).ifPresent(order -> {
                if (order.approveCalls() > 1) {
                    violations.add("결제 " + payment.id() + "(예매 " + payment.reservationId() + ")의 PG 승인 호출 approveCalls="
                            + order.approveCalls() + "(1 이하여야 한다)");
                }
            });
        }
        // PG에만 있는 승인: PG가 승인한 주문은 같은 pgTxId를 가진 결제가 있어야 한다. 결제가 아직 결과를 모르는 상태
        // (UNKNOWN·NEEDS_MANUAL_CHECK)라 pgTxId를 못 채운 것은 명시적 미해결이므로 예외다
        var byTx = new HashMap<String, PaymentRow>();
        var byOrder = new HashMap<UUID, PaymentRow>();
        for (var payment : payments) {
            if (payment.pgTxId() != null) byTx.put(payment.pgTxId(), payment);
            byOrder.put(payment.pgOrderId(), payment);
        }
        for (var approved : ledger.approvedOrders()) {
            if (byTx.containsKey(approved.pgTxId())) continue;
            var owner = byOrder.get(UUID.fromString(approved.orderId()));
            if (owner == null) {
                violations.add("PG에만 있는 승인: 주문 " + approved.orderId() + " pgTxId=" + approved.pgTxId() + " (결제 없음)");
            } else if (UNRESOLVED_PAYMENT.contains(owner.status()) && owner.pgTxId() == null) {
                continue;
            } else {
                violations.add("PG에만 있는 승인: 주문 " + approved.orderId() + " pgTxId=" + approved.pgTxId() + " 결제 "
                        + owner.id() + "는 status=" + owner.status() + " pgTxId=" + owner.pgTxId());
            }
        }
        return violations;
    }

    /** V2 환불 중복 없음. */
    List<String> v2() {
        var violations = new ArrayList<String>();
        for (var duplicated : jdbc.queryForList("""
                SELECT r.payment_id, count(*) AS refund_count FROM refunds r JOIN payments p ON p.id = r.payment_id
                WHERE p.reservation_id BETWEEN ? AND ? GROUP BY r.payment_id HAVING count(*) > 1""",
                firstReservation, lastReservation)) {
            violations.add("결제 " + duplicated.get("payment_id") + "의 환불 행이 " + duplicated.get("refund_count") + "건이다");
        }
        // 결제 DB에 pgTxId가 없어도 PG가 승인한 거래는 환불 원장을 봐야 한다
        var pgTxIds = new LinkedHashSet<String>();
        var approvedAmount = new HashMap<String, BigDecimal>();
        for (var payment : payments()) {
            if (payment.pgTxId() != null) {
                pgTxIds.add(payment.pgTxId());
                approvedAmount.put(payment.pgTxId(), payment.approvedAmount() != null ? payment.approvedAmount() : payment.amount());
            }
        }
        for (var approved : ledger.approvedOrders()) pgTxIds.add(approved.pgTxId());
        for (var pgTxId : pgTxIds) {
            var refunds = ledger.refunds(pgTxId);
            if (refunds.refundCount() > 1) {
                violations.add("PG 거래 " + pgTxId + "의 환불이 " + refunds.refundCount() + "건이다(refundCount, 1 이하여야 한다)");
            }
            var limit = approvedAmount.get(pgTxId);
            if (limit != null && refunds.refundedTotal().compareTo(limit) > 0) {
                violations.add("PG 거래 " + pgTxId + "의 환불 합계 " + refunds.refundedTotal() + "가 승인 금액 " + limit + "를 넘는다");
            }
        }
        return violations;
    }

    /** V3(결제 쪽) 모든 결제가 정상 종결이거나 명시적 미해결이고, 미해결이면 Gauge가 0보다 크다. */
    List<String> v3() {
        var violations = new ArrayList<String>();
        var unresolved = new HashSet<String>();
        for (var payment : payments()) {
            if (SETTLED_PAYMENT.contains(payment.status())) continue;
            if (UNRESOLVED_PAYMENT.contains(payment.status())) {
                unresolved.add("payment." + (payment.status().equals("UNKNOWN") ? "unknown.count" : "manual.check.count"));
                continue;
            }
            violations.add("결제 " + payment.id() + "(예매 " + payment.reservationId() + ")가 정상 종결도 명시적 미해결도 아니다 status="
                    + payment.status());
        }
        for (var refund : jdbc.queryForList("""
                SELECT r.id, r.status FROM refunds r JOIN payments p ON p.id = r.payment_id
                WHERE p.reservation_id BETWEEN ? AND ?""", firstReservation, lastReservation)) {
            var status = (String) refund.get("status");
            switch (status) {
                case "REFUNDED", "FAILED" -> { }
                case "REQUESTED", "UNKNOWN" -> unresolved.add("refund.pending.count");
                case "NEEDS_MANUAL_CHECK" -> unresolved.add("refund.manual.check.count");
                default -> violations.add("환불 " + refund.get("id") + "의 상태를 알 수 없다 status=" + status);
            }
        }
        var openDeadLetters = jdbc.queryForObject(
                "SELECT count(*) FROM dead_letters WHERE status = 'OPEN' AND source_partition = ?", Integer.class,
                deadLetterPartition);
        if (openDeadLetters != null && openDeadLetters > 0) unresolved.add("dead.letter.open.count");

        if (!unresolved.isEmpty()) {
            gauges.refresh();
            for (var name : unresolved) {
                var value = gaugeValue(name);
                if (!(value > 0)) violations.add("명시적 미해결이 있는데 Gauge " + name + "가 " + value + "이다(0보다 커야 한다)");
            }
        }
        return violations;
    }

    /**
     * V3(대사, ISE-167) 강제 대사 한 회차를 돌린 뒤 이 시나리오의 결제에 RC-7·RC-8 {@code OPEN} 불일치가 없어야 한다. 기준 시각을
     * 허용 지연(10분)보다 뒤로 줘서 시나리오가 방금 쓴 행도 판단 대상이 되게 한다(sleep 없이). 예매 서비스와 대조하는
     * RC-1~RC-6은 테스트 프로필에서 꺼져 있다(예매 내부 조회 API가 없다).
     */
    List<String> v3Reconciliation() {
        reconciliation.runOnce(Instant.now().plus(Duration.ofMinutes(11)));
        var violations = new ArrayList<String>();
        for (var mismatch : jdbc.queryForList("""
                SELECT rule, payment_id, detail::text AS detail FROM reconciliation_mismatches
                WHERE status = 'OPEN' AND reservation_id BETWEEN ? AND ? ORDER BY id""", firstReservation, lastReservation)) {
            violations.add("대사 불일치 " + mismatch.get("rule") + " OPEN 결제 " + mismatch.get("payment_id") + " "
                    + mismatch.get("detail"));
        }
        return violations;
    }

    private double gaugeValue(String name) {
        // 토픽별 Gauge(dead.letter.open.count)는 합친다
        var sum = 0.0;
        for (Gauge gauge : meters.find(name).gauges()) sum += gauge.value();
        return sum;
    }

    private List<PaymentRow> payments() {
        var rows = new ArrayList<PaymentRow>();
        jdbc.query("""
                SELECT id, reservation_id, status, pg_order_id, pg_tx_id, amount, pg_approved_amount FROM payments
                WHERE reservation_id BETWEEN ? AND ? ORDER BY id""", rs -> {
            rows.add(new PaymentRow(rs.getLong("id"), rs.getLong("reservation_id"), rs.getString("status"),
                    rs.getObject("pg_order_id", UUID.class), rs.getString("pg_tx_id"), rs.getBigDecimal("amount"),
                    rs.getBigDecimal("pg_approved_amount")));
        }, firstReservation, lastReservation);
        return rows;
    }

    private static List<String> prefixed(String name, List<String> violations) {
        return violations.stream().map(violation -> name + " " + violation).toList();
    }

    private record PaymentRow(long id, long reservationId, String status, UUID pgOrderId, String pgTxId,
            BigDecimal amount, BigDecimal approvedAmount) {
    }
}
