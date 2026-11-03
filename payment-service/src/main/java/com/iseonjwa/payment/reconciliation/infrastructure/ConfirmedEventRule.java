package com.iseonjwa.payment.reconciliation.infrastructure;

import com.iseonjwa.payment.reconciliation.domain.Finding;
import com.iseonjwa.payment.reconciliation.domain.MismatchKey;
import com.iseonjwa.payment.reconciliation.domain.ReconciliationPolicy;
import com.iseonjwa.payment.reconciliation.domain.ReconciliationRule;
import com.iseonjwa.payment.reconciliation.domain.RuleCode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RC-8: 최근 24시간 {@code target_type='PAYMENT'} 이력의 확정 전이(→ {@code APPROVED}·{@code DECLINED}·{@code REFUNDED})에
 * 대응하는 Outbox 행({@code PaymentApproved}·{@code PaymentDeclined}·{@code PaymentRefunded})이 없다. 결제 DB만으로 판단한다.
 * 키는 결제 하나이고 빠진 이벤트는 {@code detail}에 모은다.
 *
 * <p>Note: 모든 확정 전이는 {@code PaymentTransitionService}가 이력과 Outbox를 같은 트랜잭션에 쓴다(승인 API, 재확인, 환불
 * 워커, Ops 명령이 모두 이 서비스를 거친다). 이벤트를 쓰지 않는 정상 확정 전이는 없어서 규칙에서 뺀 전이가 없다. Outbox 행의
 * 대응은 {@code message_key}(예매 ID)·{@code event_type}·봉투 안 {@code payload.paymentId}로 찾는다. 이력과 같은
 * 트랜잭션에서 쓴 행이라 시각이 1시간 안으로 가까워서 그 범위로 {@code created_at} 인덱스를 쓴다. 보관 정리가
 * {@code SENT} Outbox를 지우기 전(기본 7일)의 전이만 대상이라 24시간 기간 안에서는 정상 정리가 불일치로 보이지 않는다.
 */
@Component
class ConfirmedEventRule implements ReconciliationRule {
    // 확정 전이와 같은 트랜잭션에서 쓴 Outbox 행의 시각 차이 여유
    private static final String OUTBOX_SKEW = "1 hour";

    private static final String MISSING = """
            SELECT p.id, p.reservation_id, h.to_status, h.created_at
            FROM payment_status_histories h JOIN payments p ON p.id = h.payment_id
            WHERE h.target_type = 'PAYMENT' AND h.to_status IN ('APPROVED', 'DECLINED', 'REFUNDED')
              AND h.created_at <= ?
              AND (h.created_at >= ? OR EXISTS (SELECT 1 FROM reconciliation_mismatches m
                       WHERE m.rule = 'RC-8' AND m.status = 'OPEN' AND m.payment_id = p.id))
              AND NOT EXISTS (
                  SELECT 1 FROM outbox_events o
                  WHERE o.message_key = p.reservation_id::text
                    AND o.event_type = CASE h.to_status WHEN 'APPROVED' THEN 'PaymentApproved'
                                                        WHEN 'DECLINED' THEN 'PaymentDeclined'
                                                        ELSE 'PaymentRefunded' END
                    AND o.payload -> 'payload' ->> 'paymentId' = p.id::text
                    AND o.created_at BETWEEN h.created_at - INTERVAL '%1$s' AND h.created_at + INTERVAL '%1$s')
            ORDER BY p.id, h.id
            """.formatted(OUTBOX_SKEW);

    // 허용 지연 안의 확정 전이가 있는 결제는 이번에는 판단하지 않는다
    private static final String RECENT = """
            SELECT DISTINCT p.id, p.reservation_id
            FROM payment_status_histories h JOIN payments p ON p.id = h.payment_id
            WHERE h.target_type = 'PAYMENT' AND h.to_status IN ('APPROVED', 'DECLINED', 'REFUNDED')
              AND h.created_at > ?
            """;

    private final JdbcTemplate jdbc;

    ConfirmedEventRule(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public RuleCode code() {
        return RuleCode.RC_8;
    }

    @Override
    public Evaluation evaluate(Instant now, ReconciliationPolicy policy) {
        var judgedUntil = Timestamp.from(now.minus(policy.allowedLag()));
        var since = Timestamp.from(now.minus(policy.eventWindow()));

        var missingByPayment = new LinkedHashMap<MismatchKey, List<Map<String, Object>>>();
        jdbc.query(MISSING, rs -> {
            var key = new MismatchKey(rs.getLong("reservation_id"), rs.getLong("id"));
            var transition = new LinkedHashMap<String, Object>();
            transition.put("toStatus", rs.getString("to_status"));
            transition.put("transitionedAt", rs.getTimestamp("created_at").toInstant().toString());
            missingByPayment.computeIfAbsent(key, ignored -> new ArrayList<>()).add(transition);
        }, judgedUntil, since);

        var found = new ArrayList<Finding>();
        missingByPayment.forEach((key, transitions) -> found.add(new Finding(key, Map.of("missingEvents", transitions))));

        var undecided = new HashSet<MismatchKey>();
        jdbc.query(RECENT, rs -> {
            undecided.add(new MismatchKey(rs.getLong("reservation_id"), rs.getLong("id")));
        }, judgedUntil);
        return new Evaluation(found, undecided);
    }
}
