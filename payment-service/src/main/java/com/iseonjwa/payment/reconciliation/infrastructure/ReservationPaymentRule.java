package com.iseonjwa.payment.reconciliation.infrastructure;

import com.iseonjwa.payment.reconciliation.domain.Finding;
import com.iseonjwa.payment.reconciliation.domain.MismatchKey;
import com.iseonjwa.payment.reconciliation.domain.Preparation;
import com.iseonjwa.payment.reconciliation.domain.ReconciliationPolicy;
import com.iseonjwa.payment.reconciliation.domain.ReconciliationRound;
import com.iseonjwa.payment.reconciliation.domain.ReconciliationRule;
import com.iseonjwa.payment.reconciliation.domain.ReservationSnapshot;
import com.iseonjwa.payment.reconciliation.domain.RuleCode;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * 결제에서 출발해 예매와 대조하는 규칙(RC-1·2·3·5·6)의 공통 뼈대. 결제·예매 모두 키는 {@code (예매 ID, 결제 ID)}다.
 *
 * <p>준비(트랜잭션 밖)는 회차 문맥의 예매 스냅숏을 받아 오는 것이 전부이고, 판단(잠금을 잡은 트랜잭션 안)은 결제 DB를 다시 읽어
 * 스냅숏과 비교한다. 대상은 기간 안에 갱신된 결제와 이 규칙의 {@code OPEN} 행이 가리키는 결제다. 규칙마다 달라지는 것은
 * 대상을 좁히는 SQL 조건({@link #paymentCondition})과 불일치 판정({@link #mismatch})뿐이다.
 *
 * <p>Note: 허용 지연은 결제·환불·예매 중 가장 늦은 갱신 시각 기준이다. 예매가 없으면(스냅숏에서 빠짐) 결제·환불 시각만 쓴다.
 * 준비 때 예매 ID를 묻지 않은 결제(준비 뒤에 생기거나 승인된 결제)와 허용 지연 안의 결제는 판단을 미뤄 {@code OPEN}을 닫지
 * 않는다. 스냅숏이 판단 시점보다 몇 초 오래됐을 수 있어, 그 사이 예매가 바뀐 대상은 한 회차 동안 잘못 기록될 수 있고 다음
 * 회차에 닫힌다.
 */
abstract class ReservationPaymentRule implements ReconciliationRule {
    private static final String CANDIDATES = """
            SELECT p.id, p.reservation_id, p.user_id, p.amount, p.status, p.pg_approved_amount, p.updated_at,
                   r.amount AS refund_amount, r.status AS refund_status, r.updated_at AS refund_updated_at
            FROM payments p LEFT JOIN refunds r ON r.payment_id = p.id
            WHERE %s
              AND (p.updated_at >= ? OR r.updated_at >= ?
                   OR EXISTS (SELECT 1 FROM reconciliation_mismatches m
                              WHERE m.rule = ? AND m.status = 'OPEN' AND m.payment_id = p.id))
            ORDER BY p.id
            """;

    private final JdbcTemplate jdbc;
    private final RuleCode code;

    ReservationPaymentRule(JdbcTemplate jdbc, RuleCode code) {
        this.jdbc = jdbc;
        this.code = code;
    }

    @Override
    public RuleCode code() {
        return code;
    }

    /** 이 규칙이 보는 결제를 고르는 {@code payments p}·{@code refunds r} 조건. */
    protected abstract String paymentCondition();

    /** @param reservation 예매 서비스에 없으면 null @return 불일치면 상세에 더할 사유, 아니면 비어 있다 */
    protected abstract Optional<String> mismatch(PaymentRow payment, ReservationSnapshot reservation);

    @Override
    public Preparation prepare(ReconciliationRound round) {
        return new Preparation(round.reservations());
    }

    @Override
    public Evaluation evaluate(Instant now, ReconciliationPolicy policy, Preparation preparation) {
        var view = preparation.reservations();
        var since = Timestamp.from(now.minus(policy.window()));
        var judgedUntil = now.minus(policy.allowedLag());
        var found = new ArrayList<Finding>();
        var undecided = new HashSet<MismatchKey>();
        jdbc.query(CANDIDATES.formatted(paymentCondition()), rs -> {
            var payment = PaymentRow.read(rs);
            var key = new MismatchKey(payment.reservationId(), payment.id());
            if (!view.wasRequested(payment.reservationId())) {
                undecided.add(key);
                return;
            }
            var reservation = view.find(payment.reservationId());
            if (payment.lastUpdated(reservation).isAfter(judgedUntil)) {
                undecided.add(key);
                return;
            }
            mismatch(payment, reservation).ifPresent(reason -> found.add(new Finding(key, detail(reason, payment, reservation))));
        }, since, since, code.code());
        return new Evaluation(found, undecided);
    }

    private static Map<String, Object> detail(String reason, PaymentRow payment, ReservationSnapshot reservation) {
        var detail = new LinkedHashMap<String, Object>();
        detail.put("reason", reason);
        detail.put("payment", payment.toDetail());
        detail.put("reservation", reservation == null ? null : reservationDetail(reservation));
        return detail;
    }

    static Map<String, Object> reservationDetail(ReservationSnapshot reservation) {
        var detail = new LinkedHashMap<String, Object>();
        detail.put("status", reservation.status().name());
        detail.put("totalAmount", reservation.totalAmount());
        detail.put("userId", reservation.userId());
        detail.put("paymentId", reservation.paymentId());
        detail.put("updatedAt", reservation.updatedAt().toString());
        return detail;
    }

    /** 결제와 환불(없으면 null 필드) 한 행. */
    record PaymentRow(long id, long reservationId, long userId, BigDecimal amount, String status,
            BigDecimal pgApprovedAmount, Instant updatedAt, BigDecimal refundAmount, String refundStatus,
            Instant refundUpdatedAt) {
        static PaymentRow read(ResultSet rs) throws SQLException {
            var refundUpdated = rs.getTimestamp("refund_updated_at");
            return new PaymentRow(rs.getLong("id"), rs.getLong("reservation_id"), rs.getLong("user_id"),
                    rs.getBigDecimal("amount"), rs.getString("status"), rs.getBigDecimal("pg_approved_amount"),
                    rs.getTimestamp("updated_at").toInstant(), rs.getBigDecimal("refund_amount"),
                    rs.getString("refund_status"), refundUpdated == null ? null : refundUpdated.toInstant());
        }

        /** 결제·환불·예매(있으면) 중 가장 늦은 갱신 시각. */
        Instant lastUpdated(ReservationSnapshot reservation) {
            return Stream.of(updatedAt, refundUpdatedAt, reservation == null ? null : reservation.updatedAt())
                    .filter(java.util.Objects::nonNull).max(Instant::compareTo).orElseThrow();
        }

        Map<String, Object> toDetail() {
            var detail = new LinkedHashMap<String, Object>();
            detail.put("status", status);
            detail.put("amount", amount);
            detail.put("userId", userId);
            detail.put("pgApprovedAmount", pgApprovedAmount);
            detail.put("updatedAt", updatedAt.toString());
            if (refundStatus != null) {
                detail.put("refundStatus", refundStatus);
                detail.put("refundAmount", refundAmount);
                detail.put("refundUpdatedAt", refundUpdatedAt.toString());
            }
            return detail;
        }
    }
}
