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
import java.util.Objects;

/**
 * RC-7: 결제·환불의 현재 상태가 {@code target_type}별 이력의 마지막 {@code to_status}와 다르다(직접 SQL 등으로 이력 없이
 * 바꾼 상태). 결제 DB만으로 판단한다. 키는 결제 하나이고 결제·환불 어긋남은 {@code detail}에 함께 담는다.
 *
 * <p>대상은 기간 안에 갱신된 결제(환불 갱신 포함)와 이 규칙의 {@code OPEN} 행이 가리키는 결제다. 마지막 이력은
 * {@code DISTINCT ON}으로 한 번에 구한다(결제마다 조회하지 않는다).
 *
 * <p>Note: 결제는 생성 때 이력을 쓰지 않는다. 그래서 결제 이력이 없으면 {@code REQUESTED}를 기대 상태로 본다. 환불은
 * 생성 때도 이력을 쓰므로 환불 이력이 없는데 환불 행이 있으면 불일치다. 상태만 바꾸고 {@code updated_at}을 건드리지 않은 직접
 * SQL은 기간 밖의 오래된 결제에서는 찾지 못한다(이미 {@code OPEN}이면 계속 다시 판단한다).
 */
@Component
class StatusHistoryRule implements ReconciliationRule {
    private static final String INITIAL_PAYMENT_STATUS = "REQUESTED";

    private static final String CANDIDATES = """
            WITH candidates AS (
                SELECT p.id, p.reservation_id, p.status AS payment_status, r.status AS refund_status,
                       GREATEST(p.updated_at, COALESCE(r.updated_at, p.updated_at)) AS last_updated
                FROM payments p LEFT JOIN refunds r ON r.payment_id = p.id
                WHERE p.updated_at >= ? OR r.updated_at >= ?
                   OR EXISTS (SELECT 1 FROM reconciliation_mismatches m
                              WHERE m.rule = 'RC-7' AND m.status = 'OPEN' AND m.payment_id = p.id)
            ), last_history AS (
                SELECT DISTINCT ON (h.payment_id, h.target_type) h.payment_id, h.target_type, h.to_status
                FROM payment_status_histories h
                WHERE h.payment_id IN (SELECT id FROM candidates)
                ORDER BY h.payment_id, h.target_type, h.id DESC
            )
            SELECT c.id, c.reservation_id, c.payment_status, c.refund_status, c.last_updated,
                   hp.to_status AS payment_history_status, hr.to_status AS refund_history_status
            FROM candidates c
            LEFT JOIN last_history hp ON hp.payment_id = c.id AND hp.target_type = 'PAYMENT'
            LEFT JOIN last_history hr ON hr.payment_id = c.id AND hr.target_type = 'REFUND'
            """;

    private final JdbcTemplate jdbc;

    StatusHistoryRule(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public RuleCode code() {
        return RuleCode.RC_7;
    }

    @Override
    public Evaluation evaluate(Instant now, ReconciliationPolicy policy) {
        var since = Timestamp.from(now.minus(policy.window()));
        var judgedUntil = now.minus(policy.allowedLag());
        var found = new ArrayList<Finding>();
        var undecided = new HashSet<MismatchKey>();
        jdbc.query(CANDIDATES, rs -> {
            var key = new MismatchKey(rs.getLong("reservation_id"), rs.getLong("id"));
            if (rs.getTimestamp("last_updated").toInstant().isAfter(judgedUntil)) {
                undecided.add(key);
                return;
            }
            var paymentStatus = rs.getString("payment_status");
            var paymentHistory = rs.getString("payment_history_status");
            var refundStatus = rs.getString("refund_status");
            var refundHistory = rs.getString("refund_history_status");
            var expectedPayment = paymentHistory == null ? INITIAL_PAYMENT_STATUS : paymentHistory;
            var paymentDiffers = !paymentStatus.equals(expectedPayment);
            var refundDiffers = !Objects.equals(refundStatus, refundHistory);
            if (!paymentDiffers && !refundDiffers) return;

            var detail = new LinkedHashMap<String, Object>();
            if (paymentDiffers) {
                detail.put("payment", statusPair(paymentStatus, paymentHistory));
            }
            if (refundDiffers) {
                detail.put("refund", statusPair(refundStatus, refundHistory));
            }
            detail.put("lastUpdatedAt", rs.getTimestamp("last_updated").toInstant().toString());
            found.add(new Finding(key, detail));
        }, since, since);
        return new Evaluation(found, undecided);
    }

    private static LinkedHashMap<String, Object> statusPair(String current, String lastHistory) {
        var pair = new LinkedHashMap<String, Object>();
        pair.put("currentStatus", current);
        pair.put("lastHistoryStatus", lastHistory);
        return pair;
    }
}
