package com.iseonjwa.payment.reconciliation.infrastructure;

import com.iseonjwa.payment.reconciliation.domain.Finding;
import com.iseonjwa.payment.reconciliation.domain.MismatchKey;
import com.iseonjwa.payment.reconciliation.domain.Preparation;
import com.iseonjwa.payment.reconciliation.domain.ReconciliationPolicy;
import com.iseonjwa.payment.reconciliation.domain.ReconciliationRound;
import com.iseonjwa.payment.reconciliation.domain.ReconciliationRule;
import com.iseonjwa.payment.reconciliation.domain.ReservationSnapshot;
import com.iseonjwa.payment.reconciliation.domain.ReservationStatus;
import com.iseonjwa.payment.reconciliation.domain.RuleCode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * RC-4: 예매 {@code CONFIRMED}인데 {@code paymentId} 결제가 없거나, 그 결제가 {@code APPROVED}가 아니거나(RC-3이 맡는
 * {@code REFUNDED}는 제외), 그 결제의 {@code reservation_id}가 이 예매가 아니다. 승인 없는 확정이다.
 *
 * <p>결제에서 출발하면 결제가 없는 확정 예매를 찾지 못해 예매 쪽에서 출발한다. 대상은 변경 목록(기간 안에 {@code CONFIRMED}가 된
 * 예매)과 이 규칙의 {@code OPEN} 행이 가리키는 예매다. {@code OPEN} 행의 예매는 묶음 조회로 다시 확인하고, 이미 예매
 * 서비스에서 {@code CONFIRMED}가 아니게 됐거나 없어졌으면 불일치가 아니다. 키는 {@code (예매 ID, 예매의 paymentId)}이고 결제가
 * 없으면 결제 ID는 예매가 가리키는 값(없으면 null)이다.
 */
class ConfirmedWithoutPaymentRule implements ReconciliationRule {
    private static final int CHUNK = 500;
    private static final String PAYMENTS = """
            SELECT p.id, p.reservation_id, p.status, p.updated_at, r.updated_at AS refund_updated_at
            FROM payments p LEFT JOIN refunds r ON r.payment_id = p.id
            WHERE p.id IN (:ids)
            """;

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;

    ConfirmedWithoutPaymentRule(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.named = new NamedParameterJdbcTemplate(jdbc);
    }

    @Override
    public RuleCode code() {
        return RuleCode.RC_4;
    }

    @Override
    public Preparation prepare(ReconciliationRound round) {
        return new Preparation(round.reservations());
    }

    @Override
    public Evaluation evaluate(Instant now, ReconciliationPolicy policy, Preparation preparation) {
        var view = preparation.reservations();
        var judgedUntil = now.minus(policy.allowedLag());
        var found = new ArrayList<Finding>();
        var undecided = new HashSet<MismatchKey>();

        // 대상: 변경 목록의 예매 + OPEN 행의 예매(스냅숏 쪽 상태로 다시 확인한다)
        var targets = new LinkedHashMap<Long, ReservationSnapshot>();
        for (var snapshot : view.changes()) targets.put(snapshot.reservationId(), snapshot);
        jdbc.query("SELECT reservation_id, payment_id FROM reconciliation_mismatches WHERE rule = 'RC-4' AND status = 'OPEN'",
                rs -> {
                    var reservationId = rs.getLong("reservation_id");
                    if (targets.containsKey(reservationId)) return;
                    if (!view.wasRequested(reservationId)) {
                        // 준비 뒤에 생긴 OPEN 행이다. 스냅숏이 없으니 이번에는 판단하지 않는다
                        var paymentId = rs.getObject("payment_id");
                        undecided.add(new MismatchKey(reservationId, paymentId == null ? null : ((Number) paymentId).longValue()));
                        return;
                    }
                    var snapshot = view.find(reservationId);
                    if (snapshot != null) targets.put(reservationId, snapshot);
                    // 예매 서비스에 더는 없으면 CONFIRMED일 수 없으니 불일치가 아니다(닫는다)
                });

        var confirmed = targets.values().stream().filter(s -> s.status() == ReservationStatus.CONFIRMED).toList();
        var payments = readPayments(confirmed.stream().map(ReservationSnapshot::paymentId).filter(Objects::nonNull).distinct().toList());
        for (var snapshot : confirmed) {
            var key = new MismatchKey(snapshot.reservationId(), snapshot.paymentId());
            var payment = snapshot.paymentId() == null ? null : payments.get(snapshot.paymentId());
            var lastUpdated = Stream.of(snapshot.updatedAt(), payment == null ? null : payment.updatedAt(),
                    payment == null ? null : payment.refundUpdatedAt()).filter(Objects::nonNull)
                    .max(Instant::compareTo).orElseThrow();
            if (lastUpdated.isAfter(judgedUntil)) {
                undecided.add(key);
                continue;
            }
            var reason = reason(snapshot, payment);
            if (reason != null) found.add(new Finding(key, detail(reason, snapshot, payment)));
        }
        return new Evaluation(found, undecided);
    }

    private static String reason(ReservationSnapshot snapshot, PaymentInfo payment) {
        if (snapshot.paymentId() == null) return "예매는 확정됐는데 결제 ID가 없다";
        if (payment == null) return "예매는 확정됐는데 그 결제가 없다";
        if (!payment.status().equals("APPROVED") && !payment.status().equals("REFUNDED")) {
            return "예매는 확정됐는데 결제가 " + payment.status() + "이다";
        }
        if (payment.reservationId() != snapshot.reservationId()) return "예매가 가리키는 결제가 다른 예매의 결제다";
        return null;
    }

    private static Map<String, Object> detail(String reason, ReservationSnapshot snapshot, PaymentInfo payment) {
        var detail = new LinkedHashMap<String, Object>();
        detail.put("reason", reason);
        detail.put("reservation", ReservationPaymentRule.reservationDetail(snapshot));
        if (payment == null) {
            detail.put("payment", null);
        } else {
            var map = new LinkedHashMap<String, Object>();
            map.put("id", payment.id());
            map.put("status", payment.status());
            map.put("reservationId", payment.reservationId());
            map.put("updatedAt", payment.updatedAt().toString());
            detail.put("payment", map);
        }
        return detail;
    }

    /** 결제 ID → 결제. 한 번에 넣는 ID 수를 나눠 읽는다. */
    private Map<Long, PaymentInfo> readPayments(List<Long> ids) {
        var result = new HashMap<Long, PaymentInfo>();
        for (var from = 0; from < ids.size(); from += CHUNK) {
            var chunk = ids.subList(from, Math.min(from + CHUNK, ids.size()));
            named.query(PAYMENTS, Map.of("ids", chunk), rs -> {
                var refundUpdated = rs.getTimestamp("refund_updated_at");
                result.put(rs.getLong("id"), new PaymentInfo(rs.getLong("id"), rs.getLong("reservation_id"),
                        rs.getString("status"), rs.getTimestamp("updated_at").toInstant(),
                        refundUpdated == null ? null : refundUpdated.toInstant()));
            });
        }
        return result;
    }

    private record PaymentInfo(long id, long reservationId, String status, Instant updatedAt, Instant refundUpdatedAt) {}
}
