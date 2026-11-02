package com.iseonjwa.payment.support;

import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 환불 워커 테스트용 결제·환불 행을 커밋해 만들고 지운다. 워커는 전역 후보를 집으므로 테스트는 트랜잭션 없이 커밋된 행으로
 * 돌리고, 시작 전에 다른 테스트가 남긴 환불 대상을 미래로 미뤄 둔다. 예약 ID 범위는 용도별로 나뉘어 다른 테스트와 겹치지 않는다.
 */
public final class RefundWorkerFixtures {
    public static final long AMOUNT = 50000L;
    // 서비스·쿼리 테스트 전용 범위
    private static final long SERVICE_FIRST = 9_100_000;
    private static final long SERVICE_LAST = 9_199_999;
    private static final AtomicLong SERVICE_IDS = new AtomicLong(SERVICE_FIRST);
    // 실제 Mock PG 테스트 전용 범위
    private static final long MOCK_PG_FIRST = 9_200_000;
    private static final long MOCK_PG_LAST = 9_299_999;
    private static final AtomicLong MOCK_PG_IDS = new AtomicLong(MOCK_PG_FIRST);

    private final JdbcTemplate jdbc;
    private final long first;
    private final long last;
    private final AtomicLong ids;

    private RefundWorkerFixtures(JdbcTemplate jdbc, long first, long last, AtomicLong ids) {
        this.jdbc = jdbc;
        this.first = first;
        this.last = last;
        this.ids = ids;
    }

    public static RefundWorkerFixtures forService(JdbcTemplate jdbc) {
        return new RefundWorkerFixtures(jdbc, SERVICE_FIRST, SERVICE_LAST, SERVICE_IDS);
    }

    public static RefundWorkerFixtures forMockPg(JdbcTemplate jdbc) {
        return new RefundWorkerFixtures(jdbc, MOCK_PG_FIRST, MOCK_PG_LAST, MOCK_PG_IDS);
    }

    /** 환불 대상(REQUESTED·UNKNOWN)을 모두 먼 미래로 미룬다. 이 테스트가 만들 행만 후보가 되게 하려는 것이다. */
    public void parkAllRefunds() {
        jdbc.update("UPDATE refunds SET next_check_at = now() + interval '1 year' WHERE status IN ('REQUESTED', 'UNKNOWN')");
    }

    /** 지정한 환불만 빼고 환불 대상을 모두 먼 미래로 미룬다. */
    public void parkAllRefundsExcept(long refundId) {
        jdbc.update("UPDATE refunds SET next_check_at = now() + interval '1 year' "
                + "WHERE status IN ('REQUESTED', 'UNKNOWN') AND id <> ?", refundId);
    }

    /** 만들 환불의 설명. 기본값은 APPROVED 결제의 차례가 지난 REQUESTED 환불(PG 호출 전)이다. */
    public static Spec spec() {
        return new Spec();
    }

    public static final class Spec {
        String paymentStatus = "APPROVED";
        long pgApprovedAmount = AMOUNT;
        String reason = "EXPIRED_AFTER_APPROVAL";
        String refundStatus = "REQUESTED";
        int attempt = 0;
        Instant nextCheckAt = Instant.now().minusSeconds(5);
        Instant pgRequestedAt = null;
        String pgTxId = null;

        public Spec paymentStatus(String value) { this.paymentStatus = value; return this; }
        public Spec pgApprovedAmount(long value) { this.pgApprovedAmount = value; return this; }
        public Spec reason(String value) { this.reason = value; return this; }
        public Spec refundStatus(String value) { this.refundStatus = value; return this; }
        public Spec attempt(int value) { this.attempt = value; return this; }
        public Spec nextCheckAt(Instant value) { this.nextCheckAt = value; return this; }
        public Spec pgRequestedAt(Instant value) { this.pgRequestedAt = value; return this; }
        /** 실제 Mock PG가 발급한 거래 식별자를 쓸 때 지정한다. 없으면 임의 값이다. */
        public Spec pgTxId(String value) { this.pgTxId = value; return this; }
    }

    public record Created(long paymentId, long reservationId, long refundId, UUID pgRefundId, String pgTxId) {}

    public Created create(Spec spec) {
        var reservationId = ids.incrementAndGet();
        var pgTxId = spec.pgTxId != null ? spec.pgTxId : "pg-tx-" + reservationId;
        // 금액 불일치 환불의 결제(NEEDS_MANUAL_CHECK)는 PG 승인 금액이 결제 금액과 다르다
        jdbc.update("""
                INSERT INTO payments (payment_no, reservation_id, user_id, amount, method, status, pg_order_id,
                                     pg_tx_id, pg_approved_amount, approved_at, created_at, updated_at)
                VALUES (?, ?, 42, ?, 'CARD', ?, ?, ?, ?, now(), now(), now())""",
                "PAY-W" + reservationId, reservationId, AMOUNT, spec.paymentStatus, UUID.randomUUID(), pgTxId,
                spec.pgApprovedAmount);
        var paymentId = jdbc.queryForObject("SELECT id FROM payments WHERE reservation_id = ?", Long.class, reservationId);
        var pgRefundId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO refunds (payment_id, pg_refund_id, amount, reason, source, status, attempt, next_check_at,
                                     pg_requested_at, refunded_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'EVENT', ?, ?, ?, ?, ?, now(), now())""",
                paymentId, pgRefundId, spec.pgApprovedAmount, spec.reason, spec.refundStatus, spec.attempt,
                Timestamp.from(spec.nextCheckAt), spec.pgRequestedAt == null ? null : Timestamp.from(spec.pgRequestedAt),
                "REFUNDED".equals(spec.refundStatus) ? Timestamp.from(Instant.now()) : null);
        var refundId = jdbc.queryForObject("SELECT id FROM refunds WHERE payment_id = ?", Long.class, paymentId);
        return new Created(paymentId, reservationId, refundId, pgRefundId, pgTxId);
    }

    public Created due() {
        return create(spec());
    }

    public Map<String, Object> refundRow(Created created) {
        return jdbc.queryForMap("SELECT * FROM refunds WHERE id = ?", created.refundId());
    }

    public String refundStatus(Created created) {
        return jdbc.queryForObject("SELECT status FROM refunds WHERE id = ?", String.class, created.refundId());
    }

    public int refundAttempt(Created created) {
        return jdbc.queryForObject("SELECT attempt FROM refunds WHERE id = ?", Integer.class, created.refundId());
    }

    public Instant refundNextCheckAt(Created created) {
        return jdbc.queryForObject("SELECT next_check_at FROM refunds WHERE id = ?", Timestamp.class,
                created.refundId()).toInstant();
    }

    public Instant refundPgRequestedAt(Created created) {
        var value = jdbc.queryForObject("SELECT pg_requested_at FROM refunds WHERE id = ?", Timestamp.class,
                created.refundId());
        return value == null ? null : value.toInstant();
    }

    public String paymentStatus(Created created) {
        return jdbc.queryForObject("SELECT status FROM payments WHERE id = ?", String.class, created.paymentId());
    }

    public int outboxCount(Created created) {
        return jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE message_key = ?", Integer.class,
                String.valueOf(created.reservationId()));
    }

    public List<String> outboxEventTypes(Created created) {
        return jdbc.queryForList("SELECT event_type FROM outbox_events WHERE message_key = ? ORDER BY id",
                String.class, String.valueOf(created.reservationId()));
    }

    public String outboxPayload(Created created) {
        return jdbc.queryForObject("SELECT payload::text FROM outbox_events WHERE message_key = ?", String.class,
                String.valueOf(created.reservationId()));
    }

    public int historyCount(Created created, String targetType) {
        return jdbc.queryForObject("SELECT count(*) FROM payment_status_histories WHERE payment_id = ? AND target_type = ?",
                Integer.class, created.paymentId(), targetType);
    }

    /** 가장 최근 이력의 "이전→이후:사유". 사유가 없으면 빈 문자열로 쓴다. */
    public String lastHistory(Created created, String targetType) {
        return jdbc.queryForObject("""
                SELECT from_status || '->' || to_status || ':' || coalesce(reason, '') FROM payment_status_histories
                WHERE payment_id = ? AND target_type = ? ORDER BY id DESC LIMIT 1""", String.class, created.paymentId(),
                targetType);
    }

    /** 이 범위의 결제와 딸린 환불·이력·Outbox를 지운다. 테스트 시작 전과 끝에 부른다. */
    public void cleanUp() {
        jdbc.update("DELETE FROM outbox_events WHERE message_key IN "
                + "(SELECT reservation_id::text FROM payments WHERE reservation_id BETWEEN ? AND ?)", first, last);
        jdbc.update("DELETE FROM refunds WHERE payment_id IN (SELECT id FROM payments WHERE reservation_id BETWEEN ? AND ?)",
                first, last);
        jdbc.update("DELETE FROM payment_status_histories WHERE payment_id IN "
                + "(SELECT id FROM payments WHERE reservation_id BETWEEN ? AND ?)", first, last);
        jdbc.update("DELETE FROM payments WHERE reservation_id BETWEEN ? AND ?", first, last);
    }
}
