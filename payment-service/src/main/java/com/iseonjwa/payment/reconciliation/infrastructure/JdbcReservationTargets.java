package com.iseonjwa.payment.reconciliation.infrastructure;

import com.iseonjwa.payment.reconciliation.application.ReservationTargets;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

/**
 * 예매 서비스에 물어볼 예매 ID. 기간 안에 갱신된(환불 갱신 포함) {@code APPROVED}·{@code REFUNDED} 결제의 예매와, RC-1~RC-6의
 * {@code OPEN} 행이 가리키는 예매의 합집합이다. RC-1~RC-6 규칙이 고르는 결제의 예매는 모두 여기에 든다.
 */
class JdbcReservationTargets implements ReservationTargets {
    private static final String TARGETS = """
            SELECT p.reservation_id
            FROM payments p LEFT JOIN refunds r ON r.payment_id = p.id
            WHERE p.status IN ('APPROVED', 'REFUNDED') AND (p.updated_at >= ? OR r.updated_at >= ?)
            UNION
            SELECT m.reservation_id FROM reconciliation_mismatches m
            WHERE m.status = 'OPEN' AND m.rule IN ('RC-1', 'RC-2', 'RC-3', 'RC-4', 'RC-5', 'RC-6')
              AND m.reservation_id IS NOT NULL
            """;

    private final JdbcTemplate jdbc;

    JdbcReservationTargets(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Set<Long> reservationIds(Instant since) {
        var ids = new HashSet<Long>();
        var at = Timestamp.from(since);
        jdbc.query(TARGETS, rs -> {
            ids.add(rs.getLong("reservation_id"));
        }, at, at);
        return ids;
    }
}
