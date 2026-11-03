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
 * 대응은 {@code message_key}(예매 ID)·{@code event_type}·봉투 안 {@code payload.paymentId}로 찾는다.
 *
 * <p>Note: 대응 행의 시각은 하한만 둔다({@code created_at >= 전이 시각 - 1시간}). 사람이 runbook대로 누락 이벤트를 보충하면 행이
 * 전이보다 한참 뒤에 생기는데, 상한이 있으면 보충해도 대응으로 인정하지 못해 {@code OPEN}이 닫히지 않는다.
 *
 * <p>Note: 판단 대상은 두 가지의 합집합이다. (1) 기간(24시간) 안의 확정 전이, (2) 이 규칙의 {@code OPEN} 행이 가리키는 결제의
 * 전이 중 "그 행이 처음 발견됐을 때 판단 범위에 있던 전이와 그 뒤의 전이"({@code h.created_at >= first_detected_at - (기간 +
 * 허용 지연)}). (2)를 이렇게 좁히는 이유는 보관 정리가 {@code SENT} Outbox를 지운 뒤에도 정상이던 예전 전이를 누락으로 다시
 * 판단하지 않기 위해서다. 기동 때 {@code 기간 + 허용 지연 < SENT 보관 기간}을 검증하므로 처음 발견 시점의 판단 범위 안 전이는
 * 아직 정리되지 않았다.
 *
 * <p>Note: 쿼리는 창 가지({@code idx_payment_status_histories_payment_created})와 {@code OPEN} 가지({@code
 * reconciliation_mismatches}에서 결제 ID로)의 {@code UNION}이고, 대응 확인은 {@code idx_outbox_events_message_key}로 키를
 * 찾는다. 이력 전체나 Outbox 전체를 훑지 않는다.
 */
@Component
class ConfirmedEventRule implements ReconciliationRule {
    // 확정 전이와 같은 트랜잭션에서 쓴 Outbox 행이 전이보다 앞설 수 있는 시각 차이 여유
    private static final String OUTBOX_SKEW = "1 hour";

    private static final String MISSING = """
            SELECT c.payment_id, c.reservation_id, c.history_id, c.to_status, c.created_at
            FROM (
                SELECT p.id AS payment_id, p.reservation_id, h.id AS history_id, h.to_status, h.created_at
                FROM payment_status_histories h JOIN payments p ON p.id = h.payment_id
                WHERE h.target_type = 'PAYMENT' AND h.to_status IN ('APPROVED', 'DECLINED', 'REFUNDED')
                  AND h.created_at >= ? AND h.created_at <= ?
                UNION
                SELECT p.id, p.reservation_id, h.id, h.to_status, h.created_at
                FROM reconciliation_mismatches m
                JOIN payment_status_histories h ON h.payment_id = m.payment_id
                JOIN payments p ON p.id = m.payment_id
                WHERE m.rule = 'RC-8' AND m.status = 'OPEN'
                  AND h.target_type = 'PAYMENT' AND h.to_status IN ('APPROVED', 'DECLINED', 'REFUNDED')
                  AND h.created_at <= ?
                  AND h.created_at >= m.first_detected_at - make_interval(secs => ?)
            ) c
            WHERE NOT EXISTS (
                SELECT 1 FROM outbox_events o
                WHERE o.message_key = c.reservation_id::text
                  AND o.event_type = CASE c.to_status WHEN 'APPROVED' THEN 'PaymentApproved'
                                                      WHEN 'DECLINED' THEN 'PaymentDeclined'
                                                      ELSE 'PaymentRefunded' END
                  AND o.payload -> 'payload' ->> 'paymentId' = c.payment_id::text
                  AND o.created_at >= c.created_at - INTERVAL '%s')
            ORDER BY c.payment_id, c.history_id
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
        var reach = policy.eventWindow().plus(policy.allowedLag());
        jdbc.query(MISSING, rs -> {
            var key = new MismatchKey(rs.getLong("reservation_id"), rs.getLong("payment_id"));
            var transition = new LinkedHashMap<String, Object>();
            transition.put("toStatus", rs.getString("to_status"));
            transition.put("transitionedAt", rs.getTimestamp("created_at").toInstant().toString());
            missingByPayment.computeIfAbsent(key, ignored -> new ArrayList<>()).add(transition);
        }, since, judgedUntil, judgedUntil, reach.toSeconds());

        var found = new ArrayList<Finding>();
        missingByPayment.forEach((key, transitions) -> found.add(new Finding(key, Map.of("missingEvents", transitions))));

        var undecided = new HashSet<MismatchKey>();
        jdbc.query(RECENT, rs -> {
            undecided.add(new MismatchKey(rs.getLong("reservation_id"), rs.getLong("id")));
        }, judgedUntil);
        return new Evaluation(found, undecided);
    }
}
