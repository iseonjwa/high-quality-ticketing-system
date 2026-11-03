package com.iseonjwa.payment.ops.infrastructure;

import com.iseonjwa.payment.ops.domain.OpsInspectionRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * JDBC로 조사용 읽기 쿼리를 실행한다. 엔티티를 거치지 않고 필요한 컬럼만 읽는다.
 *
 * <p>Note: 읽기만 한다. 같은 키의 Outbox·{@code dead_letters}를 보는 {@code message_key}는 예매 ID 문자열이다
 * (Outbox는 결제 결과 이벤트의 키, {@code dead_letters}는 원본 메시지의 키).
 */
@Repository
public class JdbcOpsInspectionRepository implements OpsInspectionRepository {
    private static final int PAYLOAD_HEAD_LENGTH = 200;

    private final JdbcTemplate jdbc;

    public JdbcOpsInspectionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<PaymentView> findPayment(long paymentId) {
        return jdbc.query("""
                SELECT id, payment_no, reservation_id, user_id, amount, currency, method, status, pg_order_id, pg_tx_id,
                       pg_approved_amount, decline_code, unknown_retry_count, next_check_at, approved_at, refunded_at,
                       created_at, updated_at
                FROM payments WHERE id = ?
                """, (rs, row) -> new PaymentView(rs.getLong("id"), rs.getString("payment_no"),
                rs.getLong("reservation_id"), rs.getLong("user_id"), rs.getBigDecimal("amount"),
                rs.getString("currency"), rs.getString("method"), rs.getString("status"),
                rs.getObject("pg_order_id", UUID.class), rs.getString("pg_tx_id"),
                rs.getBigDecimal("pg_approved_amount"), rs.getString("decline_code"),
                rs.getInt("unknown_retry_count"), instant(rs, "next_check_at"), instant(rs, "approved_at"),
                instant(rs, "refunded_at"), instant(rs, "created_at"), instant(rs, "updated_at")), paymentId)
                .stream().findFirst();
    }

    @Override
    public Optional<Long> findPaymentIdByReservationId(long reservationId) {
        return jdbc.queryForList("SELECT id FROM payments WHERE reservation_id = ?", Long.class, reservationId)
                .stream().findFirst();
    }

    @Override
    public Optional<Long> findPaymentIdByRefundId(long refundId) {
        return jdbc.queryForList("SELECT payment_id FROM refunds WHERE id = ?", Long.class, refundId)
                .stream().findFirst();
    }

    @Override
    public List<HistoryView> findHistories(long paymentId) {
        return jdbc.query("""
                SELECT id, target_type, from_status, to_status, reason, actor, created_at
                FROM payment_status_histories WHERE payment_id = ? ORDER BY id
                """, (rs, row) -> new HistoryView(rs.getLong("id"), rs.getString("target_type"),
                rs.getString("from_status"), rs.getString("to_status"), rs.getString("reason"), rs.getString("actor"),
                instant(rs, "created_at")), paymentId);
    }

    @Override
    public Optional<RefundView> findRefundByPaymentId(long paymentId) {
        return jdbc.query("""
                SELECT id, payment_id, pg_refund_id, amount, reason, source, status, attempt, next_check_at,
                       pg_requested_at, pg_refund_ref, refunded_at, created_at, updated_at
                FROM refunds WHERE payment_id = ?
                """, (rs, row) -> new RefundView(rs.getLong("id"), rs.getLong("payment_id"),
                rs.getObject("pg_refund_id", UUID.class), rs.getBigDecimal("amount"), rs.getString("reason"),
                rs.getString("source"), rs.getString("status"), rs.getInt("attempt"), instant(rs, "next_check_at"),
                instant(rs, "pg_requested_at"), rs.getString("pg_refund_ref"), instant(rs, "refunded_at"),
                instant(rs, "created_at"), instant(rs, "updated_at")), paymentId).stream().findFirst();
    }

    @Override
    public Optional<OutboxView> findOutbox(long outboxId) {
        return jdbc.query(OUTBOX + " WHERE id = ?", OUTBOX_MAPPER, outboxId).stream().findFirst();
    }

    @Override
    public List<OutboxView> findOutboxByKey(String messageKey) {
        return jdbc.query(OUTBOX + " WHERE message_key = ? ORDER BY id", OUTBOX_MAPPER, messageKey);
    }

    @Override
    public OutboxBacklog findOutboxBacklog(OutboxView row) {
        return jdbc.queryForObject("""
                SELECT count(*) FILTER (WHERE id > ? AND status = 'PENDING') AS pending_behind,
                       count(*) FILTER (WHERE id > ? AND status = 'FAILED') AS failed_behind,
                       count(*) FILTER (WHERE id < ? AND status = 'FAILED') AS failed_ahead
                FROM outbox_events WHERE message_key = ? AND (status = 'PENDING' OR status = 'FAILED')
                """, (rs, n) -> new OutboxBacklog(rs.getLong("pending_behind"), rs.getLong("failed_behind"),
                rs.getLong("failed_ahead")), row.id(), row.id(), row.id(), row.messageKey());
    }

    @Override
    public List<DeadLetterView> findDeadLettersByKey(String messageKey) {
        return jdbc.query("""
                SELECT id, source_topic, message_key, event_type, status, resolution, reason,
                       left(payload_raw, %d) AS payload_head, created_at
                FROM dead_letters WHERE message_key = ? ORDER BY id
                """.formatted(PAYLOAD_HEAD_LENGTH), (rs, row) -> new DeadLetterView(rs.getLong("id"),
                rs.getString("source_topic"), rs.getString("message_key"), rs.getString("event_type"),
                rs.getString("status"), rs.getString("resolution"), rs.getString("reason"),
                rs.getString("payload_head"), instant(rs, "created_at")), messageKey);
    }

    @Override
    public List<ManualLogView> findManualActionLogs(List<Long> paymentIds, List<Long> refundIds, List<Long> outboxIds,
            List<Long> deadLetterIds) {
        var conditions = new ArrayList<String>();
        addTargets(conditions, "PAYMENT", paymentIds);
        addTargets(conditions, "REFUND", refundIds);
        addTargets(conditions, "OUTBOX", outboxIds);
        addTargets(conditions, "DEAD_LETTER", deadLetterIds);
        if (conditions.isEmpty()) return List.of();
        return jdbc.query("""
                SELECT id, command, target_type, target_id, result, from_status, to_status, operator, approver, reason,
                       performed_at
                FROM manual_action_logs WHERE %s ORDER BY id
                """.formatted(String.join(" OR ", conditions)), (rs, row) -> new ManualLogView(rs.getLong("id"),
                rs.getString("command"), rs.getString("target_type"), rs.getLong("target_id"), rs.getString("result"),
                rs.getString("from_status"), rs.getString("to_status"), rs.getString("operator"),
                rs.getString("approver"), rs.getString("reason"), instant(rs, "performed_at")));
    }

    // Note: type은 코드의 상수이고 id는 DB에서 읽은 Long이라 문자열로 이어 붙여도 외부 입력이 SQL에 들어가지 않는다
    private static void addTargets(List<String> conditions, String type, List<Long> ids) {
        if (ids.isEmpty()) return;
        var list = String.join(", ", ids.stream().map(id -> Long.toString(id)).toList());
        conditions.add("(target_type = '" + type + "' AND target_id IN (" + list + "))");
    }

    private static final String OUTBOX = """
            SELECT id, event_id, topic, message_key, event_type, status, attempts, next_attempt_at, last_error,
                   created_at, sent_at
            FROM outbox_events""";

    private static final RowMapper<OutboxView> OUTBOX_MAPPER = (rs, row) -> new OutboxView(rs.getLong("id"),
            rs.getObject("event_id", UUID.class), rs.getString("topic"), rs.getString("message_key"),
            rs.getString("event_type"), rs.getString("status"), rs.getInt("attempts"),
            instant(rs, "next_attempt_at"), rs.getString("last_error"), instant(rs, "created_at"),
            instant(rs, "sent_at"));

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        var timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }
}
