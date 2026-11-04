package com.iseonjwa.payment.reconciliation.infrastructure;

import com.iseonjwa.payment.reconciliation.domain.Finding;
import com.iseonjwa.payment.reconciliation.domain.MismatchKey;
import com.iseonjwa.payment.reconciliation.domain.MismatchRepository;
import com.iseonjwa.payment.reconciliation.domain.RuleCode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Repository
class JdbcMismatchRepository implements MismatchRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    JdbcMismatchRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public boolean tryLock(RuleCode rule) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT pg_try_advisory_xact_lock(?)", Boolean.class,
                MismatchRepository.lockKey(rule)));
    }

    @Override
    public Set<MismatchKey> findOpenKeys(RuleCode rule) {
        var keys = new HashSet<MismatchKey>();
        jdbc.query("SELECT reservation_id, payment_id FROM reconciliation_mismatches WHERE rule = ? AND status = 'OPEN'",
                rs -> {
                    keys.add(new MismatchKey(nullableLong(rs.getObject("reservation_id")),
                            nullableLong(rs.getObject("payment_id"))));
                }, rule.code());
        return keys;
    }

    @Override
    public void upsertOpen(RuleCode rule, List<Finding> findings, Instant now) {
        if (findings.isEmpty()) return;
        var at = Timestamp.from(now);
        // 충돌 대상은 부분 유니크 인덱스(uq_reconciliation_mismatches_open)의 식과 같아야 한다
        jdbc.batchUpdate("""
                INSERT INTO reconciliation_mismatches
                    (rule, reservation_id, payment_id, detail, status, first_detected_at, last_seen_at)
                VALUES (?, ?, ?, CAST(? AS jsonb), 'OPEN', ?, ?)
                ON CONFLICT (rule, COALESCE(reservation_id, 0), COALESCE(payment_id, 0)) WHERE status = 'OPEN'
                DO UPDATE SET detail = EXCLUDED.detail, last_seen_at = EXCLUDED.last_seen_at
                """, findings, findings.size(), (statement, finding) -> {
            statement.setString(1, rule.code());
            setLong(statement, 2, finding.key().reservationId());
            setLong(statement, 3, finding.key().paymentId());
            statement.setString(4, json.writeValueAsString(finding.detail()));
            statement.setTimestamp(5, at);
            statement.setTimestamp(6, at);
        });
    }

    @Override
    public int resolve(RuleCode rule, Collection<MismatchKey> keys, Instant now) {
        if (keys.isEmpty()) return 0;
        var at = Timestamp.from(now);
        var closed = 0;
        for (var batch : jdbc.batchUpdate("""
                UPDATE reconciliation_mismatches SET status = 'RESOLVED', resolved_at = ?
                WHERE rule = ? AND status = 'OPEN'
                  AND COALESCE(reservation_id, 0) = COALESCE(?, 0) AND COALESCE(payment_id, 0) = COALESCE(?, 0)
                """, List.copyOf(keys), keys.size(), (statement, key) -> {
            statement.setTimestamp(1, at);
            statement.setString(2, rule.code());
            setLong(statement, 3, key.reservationId());
            setLong(statement, 4, key.paymentId());
        })) {
            for (var count : batch) closed += count;
        }
        return closed;
    }

    // 타입 없는 null은 COALESCE(?, 0)에서 PostgreSQL이 자료형을 정하지 못하므로 BIGINT로 명시한다
    private static void setLong(java.sql.PreparedStatement statement, int index, Long value) throws java.sql.SQLException {
        if (value == null) statement.setNull(index, java.sql.Types.BIGINT);
        else statement.setLong(index, value);
    }

    private static Long nullableLong(Object value) {
        return value == null ? null : ((Number) value).longValue();
    }
}
