package com.iseonjwa.payment.ops;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/** Ops 테스트가 쓰는 결제·환불·Outbox·dead_letters 행을 만든다. 이 범위의 예매 ID는 Ops 테스트만 쓴다. */
final class OpsFixtures {
    static final long FIRST_RESERVATION = 9_100_000;
    static final long LAST_RESERVATION = 9_199_999;
    private static final AtomicLong RESERVATIONS = new AtomicLong(FIRST_RESERVATION);

    private final JdbcTemplate jdbc;

    OpsFixtures(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    static long nextReservation() {
        return RESERVATIONS.incrementAndGet();
    }

    /** 승인된 결제(금액 50000)를 만들고 id를 돌려준다. */
    long approvedPayment(long reservationId) {
        jdbc.update("""
                INSERT INTO payments (payment_no, reservation_id, user_id, amount, method, status, pg_order_id, pg_tx_id,
                                     pg_approved_amount, approved_at, created_at, updated_at)
                VALUES (?, ?, 7, 50000, 'CARD', 'APPROVED', ?, ?, 50000, now(), now(), now())
                """, "PAY-O" + reservationId, reservationId, UUID.randomUUID(), "pg-tx-" + reservationId);
        return jdbc.queryForObject("SELECT id FROM payments WHERE reservation_id = ?", Long.class, reservationId);
    }

    void history(long paymentId, String targetType, String from, String to, String actor) {
        jdbc.update("""
                INSERT INTO payment_status_histories (payment_id, target_type, from_status, to_status, actor, created_at)
                VALUES (?, ?, ?, ?, ?, now())
                """, paymentId, targetType, from, to, actor);
    }

    long refund(long paymentId, String status) {
        var refunded = status.equals("REFUNDED");
        var scheduled = status.equals("REQUESTED") || status.equals("UNKNOWN");
        return jdbc.queryForObject("""
                INSERT INTO refunds (payment_id, pg_refund_id, amount, reason, source, status, next_check_at, refunded_at,
                                    created_at, updated_at)
                VALUES (?, ?, 50000, 'CANCELED', 'EVENT', ?, %s, %s, now(), now()) RETURNING id
                """.formatted(scheduled ? "now()" : "NULL", refunded ? "now()" : "NULL"), Long.class, paymentId,
                UUID.randomUUID(), status);
    }

    long outbox(String key, String eventType, String status, int attempts, String lastError) {
        return jdbc.queryForObject("""
                INSERT INTO outbox_events (event_id, topic, message_key, event_type, payload, status, attempts,
                                          next_attempt_at, claimed_until, last_error, created_at)
                VALUES (?, 'payment.result', ?, ?, jsonb_build_object('eventType', ?::text), ?, ?, now() + interval '1 day',
                        now() + interval '1 day', ?, now()) RETURNING id
                """, Long.class, UUID.randomUUID(), key, eventType, eventType, status, attempts, lastError);
    }

    long deadLetter(String key, String payload) {
        return jdbc.queryForObject("""
                INSERT INTO dead_letters (source_topic, message_key, event_key, event_type, payload_raw, reason, status,
                                         created_at)
                VALUES ('payment.command.dlq', ?, ?, 'RefundRequested', ?, '검증 실패', 'OPEN', now()) RETURNING id
                """, Long.class, key, "dl-" + UUID.randomUUID(), payload);
    }
}
