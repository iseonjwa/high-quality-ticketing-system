package com.iseonjwa.payment.reconciliation;

import com.iseonjwa.payment.support.AppendOnlyCleanup;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 대사 테스트가 결제·이력·Outbox를 직접 넣는 도구. 모든 상태 조건(CHECK)을 만족하도록 상태와 상관없이 PG 거래 정보를
 * 채워 넣는다. 운영 코드를 거치지 않고 SQL로 넣으므로 "직접 SQL로 바꾼 상태" 같은 어긋난 데이터도 만들 수 있다.
 */
final class ReconciliationFixtures {
    private static final AtomicLong SEQUENCE = new AtomicLong(8_800_000);

    private final JdbcTemplate jdbc;

    ReconciliationFixtures(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 대사가 읽는 테이블을 비운다. 테스트는 트랜잭션 안에서 부르고 끝나면 롤백한다. */
    void wipe() {
        jdbc.update("DELETE FROM reconciliation_mismatches");
        jdbc.update("DELETE FROM dead_letters");
        jdbc.update("DELETE FROM refunds");
        AppendOnlyCleanup.deleteAllHistories(jdbc);
        AppendOnlyCleanup.deleteAllManualActionLogs(jdbc);
        jdbc.update("DELETE FROM idempotency_records");
        jdbc.update("DELETE FROM outbox_events");
        jdbc.update("DELETE FROM payments");
    }

    long newReservation() {
        return SEQUENCE.incrementAndGet();
    }

    /** 결제 행 하나. {@code updatedAt}이 마지막 갱신 시각이다. */
    long payment(long reservationId, String status, Instant updatedAt) {
        var sequence = SEQUENCE.incrementAndGet();
        var at = Timestamp.from(updatedAt);
        jdbc.update("""
                INSERT INTO payments (payment_no, reservation_id, user_id, amount, method, status, pg_order_id, pg_provider, pg_tx_id,
                                      approved_at, pg_approved_amount, refunded_at, decline_code, next_check_at,
                                      created_at, updated_at)
                VALUES (?, ?, 1, 50000, 'CARD', ?, ?, 'MOCK', ?, ?, 50000, ?, 'OTHER', ?, ?, ?)""",
                "PAY-" + sequence, reservationId, status, UUID.randomUUID(), "pg-tx-" + sequence, at, at, at, at, at);
        return jdbc.queryForObject("SELECT id FROM payments WHERE reservation_id = ?", Long.class, reservationId);
    }

    void paymentHistory(long paymentId, String from, String to, Instant createdAt) {
        history(paymentId, "PAYMENT", from, to, createdAt);
    }

    void history(long paymentId, String targetType, String from, String to, Instant createdAt) {
        jdbc.update("""
                INSERT INTO payment_status_histories (payment_id, target_type, from_status, to_status, actor, created_at)
                VALUES (?, ?, ?, ?, 'SYSTEM', ?)""", paymentId, targetType, from, to, Timestamp.from(createdAt));
    }

    long refund(long paymentId, String status, Instant updatedAt) {
        var at = Timestamp.from(updatedAt);
        jdbc.update("""
                INSERT INTO refunds (payment_id, pg_refund_id, amount, reason, source, status, next_check_at, refunded_at,
                                     created_at, updated_at)
                VALUES (?, ?, 50000, 'CANCELED', 'EVENT', ?, ?, ?, ?, ?)""",
                paymentId, UUID.randomUUID(), status, at, at, at, at);
        return jdbc.queryForObject("SELECT id FROM refunds WHERE payment_id = ?", Long.class, paymentId);
    }

    /** 운영 코드가 쓰는 봉투 모양({@code payload.paymentId})의 Outbox 행. */
    void outbox(long reservationId, long paymentId, String eventType, Instant createdAt) {
        var at = Timestamp.from(createdAt);
        jdbc.update("""
                INSERT INTO outbox_events (event_id, topic, message_key, event_type, payload, status, next_attempt_at,
                                           created_at)
                VALUES (?, 'payment.result', ?, ?, CAST(? AS jsonb), 'SENT', ?, ?)""",
                UUID.randomUUID(), String.valueOf(reservationId), eventType,
                "{\"payload\":{\"paymentId\":" + paymentId + ",\"reservationId\":" + reservationId + "}}", at, at);
    }
}
