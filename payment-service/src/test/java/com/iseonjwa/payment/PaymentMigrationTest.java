package com.iseonjwa.payment;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Note: 제약 위반 뒤에는 PostgreSQL이 트랜잭션을 중단 상태로 만든다.
// 테스트마다 위반을 하나만 확인하고, 클래스 전체 롤백으로 데이터를 남기지 않는다.
@SpringBootTest(properties = {"spring.config.import=", "spring.cloud.config.enabled=false", "eureka.client.enabled=false"})
@ActiveProfiles("test")
@Transactional
class PaymentMigrationTest {
    @Autowired
    JdbcTemplate jdbc;

    @Test
    void 결제_제약과_인덱스가_카탈로그에_존재한다() {
        var constraints = names("SELECT conname FROM pg_constraint WHERE conrelid = 'payments'::regclass");
        assertTrue(constraints.containsAll(List.of(
                "uq_payments_payment_no", "uq_payments_reservation_id", "uq_payments_pg_order_id", "uq_payments_pg_tx_id",
                "chk_payments_amount", "chk_payments_currency", "chk_payments_method", "chk_payments_status",
                "chk_payments_check_schedule", "chk_payments_approved", "chk_payments_refunded", "chk_payments_declined")),
                "누락된 제약이 있다: " + constraints);
        var indexes = names("SELECT indexname FROM pg_indexes WHERE tablename = 'payments'");
        assertTrue(indexes.containsAll(List.of("idx_payments_next_check_at", "idx_payments_unsettled_created_at")));
    }

    @Test
    void Outbox_릴레이용_인덱스가_카탈로그에_존재한다() {
        var indexes = names("SELECT indexname FROM pg_indexes WHERE tablename = 'outbox_events'");
        assertTrue(indexes.containsAll(List.of("idx_outbox_events_pending_next_attempt", "idx_outbox_events_pending_message_key",
                "idx_outbox_events_pending_id", "idx_outbox_events_failed_message_key")), indexes.toString());
    }

    @Test
    void 예매_하나에_결제_행을_둘_만들_수_없다() {
        var reservationId = 1001L;
        insertPayment(reservationId, "REQUESTED");
        assertViolation("uq_payments_reservation_id", () -> insertPayment(reservationId, "REQUESTED"));
    }

    @Test
    void 거절된_예매에도_두_번째_결제_행을_만들_수_없다() {
        var reservationId = 1002L;
        jdbc.update(insertSql("DECLINED", "decline_code", "'OTHER'"), args(reservationId));
        assertViolation("uq_payments_reservation_id", () -> insertPayment(reservationId, "REQUESTED"));
    }

    @Test
    void 같은_PG_거래를_두_결제에_연결할_수_없다() {
        approvedPayment(1003L, "pg-tx-duplicated");
        assertViolation("uq_payments_pg_tx_id", () -> approvedPayment(1004L, "pg-tx-duplicated"));
    }

    @Test
    void 승인_금액이_결제_금액과_다르면_APPROVED로_저장할_수_없다() {
        assertViolation("chk_payments_approved", () -> jdbc.update(
                insertSql("APPROVED", "pg_tx_id, approved_at, pg_approved_amount", "'pg-tx-1', now(), 49000"),
                args(1005L)));
    }

    @Test
    void PG_거래_식별자_없이_APPROVED로_저장할_수_없다() {
        assertViolation("chk_payments_approved", () -> jdbc.update(
                insertSql("APPROVED", "approved_at, pg_approved_amount", "now(), 50000"), args(1006L)));
    }

    @Test
    void 다음_조회_시각_없이_REQUESTED로_저장할_수_없다() {
        assertViolation("chk_payments_check_schedule", () -> jdbc.update(
                insertSql("REQUESTED", "next_check_at", "NULL"), args(1007L)));
    }

    @Test
    void 사유_없이_DECLINED로_저장할_수_없다() {
        assertViolation("chk_payments_declined", () -> jdbc.update(
                insertSql("DECLINED", "decline_code", "NULL"), args(1008L)));
    }

    @Test
    void 환불_완료_시각_없이_REFUNDED로_저장할_수_없다() {
        assertViolation("chk_payments_refunded", () -> jdbc.update(
                insertSql("REFUNDED", "pg_tx_id, refunded_at", "'pg-tx-2', NULL"), args(1009L)));
    }

    @Test
    void 원화가_아닌_통화를_거절한다() {
        assertViolation("chk_payments_currency", () -> jdbc.update(
                insertSql("REQUESTED", "next_check_at, currency", "now(), 'USD'"), args(1010L)));
    }

    @Test
    void 금액이_0원_이하인_결제를_거절한다() {
        assertViolation("chk_payments_amount", () -> jdbc.update(
                insertSql("REQUESTED", "next_check_at", "now()").replace("50000", "0"), args(1011L)));
    }

    @Test
    void 이력_테이블은_대상_종류를_제한하고_존재하지_않는_결제를_참조할_수_없다() {
        var message = assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("""
                INSERT INTO payment_status_histories (payment_id, target_type, to_status, actor, created_at)
                VALUES (999999999, 'PAYMENT', 'REQUESTED', 'SYSTEM', now())""")).getMessage();
        assertTrue(message.contains("payment_status_histories_payment_id_fkey"), message);
    }

    @Test
    void 멱등_기록은_사용자와_키를_합친_기본키를_쓴다() {
        var key = UUID.randomUUID();
        insertIdempotencyRecord(7L, key);
        assertViolation("idempotency_records_pkey", () -> insertIdempotencyRecord(7L, key));
    }

    @Test
    void 같은_멱등키라도_다른_사용자는_따로_기록한다() {
        var key = UUID.randomUUID();
        insertIdempotencyRecord(7L, key);
        insertIdempotencyRecord(8L, key);
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM idempotency_records WHERE idem_key = ?", Integer.class, key));
    }

    @Test
    void 같은_eventId는_Outbox에_두_번_들어갈_수_없다() {
        var eventId = UUID.randomUUID();
        insertOutbox(eventId);
        assertViolation("uq_outbox_events_event_id", () -> insertOutbox(eventId));
    }

    @Test
    void 같은_이벤트도_소비_그룹이_다르면_각각_처리한_것으로_기록한다() {
        var eventId = UUID.randomUUID();
        jdbc.update("INSERT INTO processed_events VALUES (?, 'group-a', now())", eventId);
        jdbc.update("INSERT INTO processed_events VALUES (?, 'group-b', now())", eventId);
        assertViolation("processed_events_pkey",
                () -> jdbc.update("INSERT INTO processed_events VALUES (?, 'group-a', now())", eventId));
    }

    @Test
    void 환불_제약과_인덱스가_카탈로그에_존재한다() {
        var constraints = names("SELECT conname FROM pg_constraint WHERE conrelid = 'refunds'::regclass");
        assertTrue(constraints.containsAll(List.of(
                "uq_refunds_payment_id", "uq_refunds_pg_refund_id", "chk_refunds_amount", "chk_refunds_reason",
                "chk_refunds_source", "chk_refunds_status", "chk_refunds_check_schedule", "chk_refunds_refunded")),
                "누락된 제약이 있다: " + constraints);
        var indexes = names("SELECT indexname FROM pg_indexes WHERE tablename = 'refunds'");
        assertTrue(indexes.containsAll(List.of("idx_refunds_next_check_at", "idx_refunds_unsettled_created_at")), indexes.toString());
    }

    @Test
    void 결제_하나에_환불_행을_둘_만들_수_없다() {
        var paymentId = approvedPaymentId(1101L);
        insertRefund(paymentId, "REQUESTED", "now()", "NULL");
        assertViolation("uq_refunds_payment_id", () -> insertRefund(paymentId, "REQUESTED", "now()", "NULL"));
    }

    @Test
    void 같은_PG_환불_ID를_두_환불에_쓸_수_없다() {
        var pgRefundId = UUID.randomUUID();
        jdbc.update(refundSql("REQUESTED", "now()", "NULL"), approvedPaymentId(1102L), pgRefundId);
        assertViolation("uq_refunds_pg_refund_id",
                () -> jdbc.update(refundSql("REQUESTED", "now()", "NULL"), approvedPaymentId(1103L), pgRefundId));
    }

    @Test
    void 다음_조회_시각_없이_REQUESTED_환불을_저장할_수_없다() {
        var paymentId = approvedPaymentId(1104L);
        assertViolation("chk_refunds_check_schedule", () -> insertRefund(paymentId, "REQUESTED", "NULL", "NULL"));
    }

    @Test
    void 다음_조회_시각_없이_UNKNOWN_환불을_저장할_수_없다() {
        var paymentId = approvedPaymentId(1105L);
        assertViolation("chk_refunds_check_schedule", () -> insertRefund(paymentId, "UNKNOWN", "NULL", "NULL"));
    }

    @Test
    void 환불_완료_시각_없이_REFUNDED_환불을_저장할_수_없다() {
        var paymentId = approvedPaymentId(1106L);
        assertViolation("chk_refunds_refunded", () -> insertRefund(paymentId, "REFUNDED", "NULL", "NULL"));
    }

    @Test
    void 정해진_값이_아닌_환불_사유를_거절한다() {
        var paymentId = approvedPaymentId(1107L);
        assertViolation("chk_refunds_reason",
                () -> jdbc.update(refundSql("REQUESTED", "now()", "NULL").replace("'CANCELED'", "'UNKNOWN_REASON'"),
                        paymentId, UUID.randomUUID()));
    }

    @Test
    void 정해진_값이_아닌_환불_경로와_상태를_거절한다() {
        var paymentId = approvedPaymentId(1108L);
        assertViolation("chk_refunds_source",
                () -> jdbc.update(refundSql("REQUESTED", "now()", "NULL").replace("'EVENT'", "'MANUAL'"),
                        paymentId, UUID.randomUUID()));
    }

    @Test
    void 알_수_없는_환불_상태를_거절한다() {
        var paymentId = approvedPaymentId(1109L);
        assertViolation("chk_refunds_status", () -> insertRefund(paymentId, "DECLINED", "now()", "NULL"));
    }

    @Test
    void 환불_금액이_0원_이하면_거절한다() {
        var paymentId = approvedPaymentId(1110L);
        assertViolation("chk_refunds_amount", () -> jdbc.update(
                refundSql("REQUESTED", "now()", "NULL").replace("50000", "0"), paymentId, UUID.randomUUID()));
    }

    @Test
    void 존재하지_않는_결제의_환불을_저장할_수_없다() {
        assertViolation("refunds_payment_id_fkey", () -> insertRefund(999_999_999L, "REQUESTED", "now()", "NULL"));
    }

    @Test
    void 환불_이력도_결제_이력_테이블에_REFUND로_저장한다() {
        var paymentId = approvedPaymentId(1111L);
        jdbc.update("""
                INSERT INTO payment_status_histories (payment_id, target_type, from_status, to_status, actor, created_at)
                VALUES (?, 'REFUND', NULL, 'REQUESTED', 'SYSTEM', now())""", paymentId);
        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM payment_status_histories WHERE payment_id = ? AND target_type = 'REFUND'",
                Integer.class, paymentId));
    }

    @Test
    void DLQ_제약과_인덱스가_카탈로그에_존재한다() {
        var constraints = names("SELECT conname FROM pg_constraint WHERE conrelid = 'dead_letters'::regclass");
        assertTrue(constraints.containsAll(List.of(
                "uq_dead_letters_event_key", "chk_dead_letters_status", "chk_dead_letters_resolution",
                "chk_dead_letters_resolved")), constraints.toString());
        var indexes = names("SELECT indexname FROM pg_indexes WHERE tablename = 'dead_letters'");
        assertTrue(indexes.contains("idx_dead_letters_open_created_at"), indexes.toString());
    }

    @Test
    void 같은_event_key를_두_번_저장할_수_없다() {
        insertDeadLetter("key-1", "OPEN", "NULL");
        assertViolation("uq_dead_letters_event_key", () -> insertDeadLetter("key-1", "OPEN", "NULL"));
    }

    @Test
    void 해결_방식_없이_RESOLVED로_저장할_수_없다() {
        assertViolation("chk_dead_letters_resolved", () -> insertDeadLetter("key-2", "RESOLVED", "NULL"));
    }

    @Test
    void 정해진_값이_아닌_DLQ_상태를_거절한다() {
        assertViolation("chk_dead_letters_status", () -> insertDeadLetter("key-3", "CLOSED", "NULL"));
    }

    @Test
    void 정해진_값이_아닌_해결_방식을_거절한다() {
        assertViolation("chk_dead_letters_resolution", () -> insertDeadLetter("key-4", "RESOLVED", "'IGNORED'"));
    }

    @Test
    void 해결_방식이_있으면_RESOLVED로_저장한다() {
        insertDeadLetter("key-5", "RESOLVED", "'NO_OBLIGATION'");
        assertEquals("NO_OBLIGATION", jdbc.queryForObject(
                "SELECT resolution FROM dead_letters WHERE event_key = 'key-5'", String.class));
    }

    @Test
    void 존재하지_않는_환불을_DLQ에_연결할_수_없다() {
        assertViolation("dead_letters_refund_id_fkey", () -> jdbc.update("""
                INSERT INTO dead_letters (event_key, payload_raw, reason, status, resolution, refund_id, created_at)
                VALUES ('key-6', 'raw', 'r', 'RESOLVED', 'DUPLICATE', 999999999, now())"""));
    }

    @Test
    void 감사_테이블_제약과_인덱스가_카탈로그에_존재한다() {
        var constraints = names("SELECT conname FROM pg_constraint WHERE conrelid = 'manual_action_logs'::regclass");
        assertTrue(constraints.containsAll(List.of(
                "chk_manual_action_logs_target_type", "chk_manual_action_logs_result",
                "chk_manual_action_logs_plan_hash", "chk_manual_action_logs_approver")), constraints.toString());
        var indexes = names("SELECT indexname FROM pg_indexes WHERE tablename = 'manual_action_logs'");
        assertTrue(indexes.contains("idx_manual_action_logs_target"), indexes.toString());
    }

    @Test
    void append_only_트리거가_카탈로그에_존재한다() {
        // tgtype 비트: 1 = 행 단위, 2 = BEFORE, 8 = DELETE, 16 = UPDATE, 32 = TRUNCATE
        var triggers = names("""
                SELECT c.relname || ':' || t.tgname || ':' || t.tgtype FROM pg_trigger t
                JOIN pg_class c ON c.oid = t.tgrelid
                WHERE NOT t.tgisinternal AND t.tgfoid = 'reject_modification'::regproc""");
        var rowBefore = 1 + 2 + 8 + 16;
        var statementBefore = 2 + 32;
        assertEquals(List.of(
                "manual_action_logs:manual_action_logs_no_truncate:" + statementBefore,
                "manual_action_logs:manual_action_logs_no_update:" + rowBefore,
                "payment_status_histories:payment_status_histories_no_truncate:" + statementBefore,
                "payment_status_histories:payment_status_histories_no_update:" + rowBefore),
                triggers.stream().sorted().toList());
    }

    @Test
    void 이력은_수정할_수_없다() {
        var paymentId = approvedPaymentId(1201L);
        insertHistory(paymentId);
        assertAppendOnly("payment_status_histories",
                () -> jdbc.update("UPDATE payment_status_histories SET actor = 'OPS:x' WHERE payment_id = ?", paymentId));
    }

    @Test
    void 이력은_삭제할_수_없다() {
        var paymentId = approvedPaymentId(1202L);
        insertHistory(paymentId);
        assertAppendOnly("payment_status_histories",
                () -> jdbc.update("DELETE FROM payment_status_histories WHERE payment_id = ?", paymentId));
    }

    @Test
    void 이력은_TRUNCATE할_수_없다() {
        assertAppendOnly("payment_status_histories", () -> jdbc.execute("TRUNCATE payment_status_histories"));
    }

    @Test
    void 감사_기록은_수정할_수_없다() {
        insertManualActionLog();
        assertAppendOnly("manual_action_logs", () -> jdbc.update("UPDATE manual_action_logs SET result = 'REJECTED'"));
    }

    @Test
    void 감사_기록은_삭제할_수_없다() {
        insertManualActionLog();
        assertAppendOnly("manual_action_logs", () -> jdbc.update("DELETE FROM manual_action_logs"));
    }

    @Test
    void 감사_기록은_TRUNCATE할_수_없다() {
        assertAppendOnly("manual_action_logs", () -> jdbc.execute("TRUNCATE manual_action_logs"));
    }

    @Test
    void 감사_기록의_승인자는_실행자와_같을_수_없다() {
        assertViolation("chk_manual_action_logs_approver", () -> insertManualActionLog("alice", "alice", "a".repeat(64)));
    }

    @Test
    void 감사_기록의_계획_해시는_소문자_16진수_64자여야_한다() {
        assertViolation("chk_manual_action_logs_plan_hash", () -> insertManualActionLog("alice", null, "A".repeat(64)));
    }

    @Test
    void 정해진_값이_아닌_감사_결과를_거절한다() {
        assertViolation("chk_manual_action_logs_result", () -> jdbc.update(
                manualActionLogSql().replace("'APPLIED'", "'DONE'"), "alice", null, "a".repeat(64)));
    }

    @Test
    void 대사_불일치의_제약과_인덱스가_카탈로그에_존재한다() {
        var constraints = names("SELECT conname FROM pg_constraint WHERE conrelid = 'reconciliation_mismatches'::regclass");
        assertTrue(constraints.containsAll(List.of("chk_reconciliation_mismatches_rule",
                "chk_reconciliation_mismatches_status", "chk_reconciliation_mismatches_resolved")), constraints.toString());
        var indexes = names("SELECT indexname FROM pg_indexes WHERE tablename = 'reconciliation_mismatches'");
        assertTrue(indexes.containsAll(List.of("uq_reconciliation_mismatches_open", "idx_reconciliation_mismatches_open",
                "idx_reconciliation_mismatches_payment", "idx_reconciliation_mismatches_reservation")), indexes.toString());
    }

    @Test
    void 같은_불일치의_OPEN_행은_둘일_수_없다_대상이_없는_쪽은_0으로_비교한다() {
        insertMismatch("RC-1", 7L, 8L, "OPEN");
        assertViolation("uq_reconciliation_mismatches_open", () -> insertMismatch("RC-1", 7L, 8L, "OPEN"));
    }

    @Test
    void 결제가_없는_불일치도_예매가_같으면_OPEN_행은_하나다() {
        insertMismatch("RC-2", 9L, null, "OPEN");
        assertViolation("uq_reconciliation_mismatches_open", () -> insertMismatch("RC-2", 9L, null, "OPEN"));
    }

    @Test
    void 닫힌_불일치는_같은_키로_여러_행을_둘_수_있다() {
        insertMismatch("RC-3", 5L, 6L, "RESOLVED");
        insertMismatch("RC-3", 5L, 6L, "RESOLVED");
        insertMismatch("RC-3", 5L, 6L, "OPEN");
    }

    @Test
    void 정해진_값이_아닌_대사_규칙을_거절한다() {
        assertViolation("chk_reconciliation_mismatches_rule", () -> insertMismatch("RC-9", 1L, 1L, "OPEN"));
    }

    @Test
    void 정해진_값이_아닌_대사_상태를_거절한다() {
        assertViolation("chk_reconciliation_mismatches_status", () -> insertMismatch("RC-1", 1L, 1L, "CLOSED"));
    }

    @Test
    void RESOLVED는_닫은_시각이_있어야_한다() {
        assertViolation("chk_reconciliation_mismatches_resolved", () -> jdbc.update("""
                INSERT INTO reconciliation_mismatches (rule, detail, status, first_detected_at, last_seen_at)
                VALUES ('RC-1', '{}'::jsonb, 'RESOLVED', now(), now())"""));
    }

    @Test
    void OPEN은_닫은_시각이_없어야_한다() {
        assertViolation("chk_reconciliation_mismatches_resolved", () -> jdbc.update("""
                INSERT INTO reconciliation_mismatches (rule, detail, status, first_detected_at, last_seen_at, resolved_at)
                VALUES ('RC-1', '{}'::jsonb, 'OPEN', now(), now(), now())"""));
    }

    private void insertMismatch(String rule, Long reservationId, Long paymentId, String status) {
        jdbc.update("""
                INSERT INTO reconciliation_mismatches (rule, reservation_id, payment_id, detail, status, first_detected_at,
                                                       last_seen_at, resolved_at)
                VALUES (?, ?, ?, '{}'::jsonb, ?, now(), now(), CASE WHEN ? = 'RESOLVED' THEN now() END)""",
                rule, reservationId, paymentId, status, status);
    }

    private void insertHistory(long paymentId) {
        jdbc.update("""
                INSERT INTO payment_status_histories (payment_id, target_type, from_status, to_status, actor, created_at)
                VALUES (?, 'PAYMENT', 'REQUESTED', 'APPROVED', 'SYSTEM', now())""", paymentId);
    }

    private void insertManualActionLog() {
        insertManualActionLog("alice", null, "a".repeat(64));
    }

    private void insertManualActionLog(String operator, String approver, String planHash) {
        jdbc.update(manualActionLogSql(), operator, approver, planHash);
    }

    private static String manualActionLogSql() {
        return """
                INSERT INTO manual_action_logs (command, target_type, target_id, from_status, to_status, result,
                                                operator, approver, plan_hash, reason, performed_at)
                VALUES ('outbox requeue', 'OUTBOX', 1, 'FAILED', 'PENDING', 'APPLIED', ?, ?, ?, 'test', now())""";
    }

    private static void assertAppendOnly(String table, Runnable action) {
        var exception = assertThrows(DataAccessException.class, action::run);
        assertTrue(exception.getMessage().contains(table + " is append-only"), exception.getMessage());
    }

    private void insertDeadLetter(String eventKey, String status, String resolution) {
        jdbc.update("""
                INSERT INTO dead_letters (event_key, payload_raw, reason, status, resolution, created_at)
                VALUES (?, 'raw', 'reason', '%s', %s, now())""".formatted(status, resolution), eventKey);
    }

    private long approvedPaymentId(long reservationId) {
        approvedPayment(reservationId, "pg-tx-" + reservationId);
        return jdbc.queryForObject("SELECT id FROM payments WHERE reservation_id = ?", Long.class, reservationId);
    }

    private void insertRefund(long paymentId, String status, String nextCheckAt, String refundedAt) {
        jdbc.update(refundSql(status, nextCheckAt, refundedAt), paymentId, UUID.randomUUID());
    }

    private static String refundSql(String status, String nextCheckAt, String refundedAt) {
        return """
                INSERT INTO refunds (payment_id, pg_refund_id, amount, reason, source, status, next_check_at, refunded_at,
                                    created_at, updated_at)
                VALUES (?, ?, 50000, 'CANCELED', 'EVENT', '%s', %s, %s, now(), now())""".formatted(status, nextCheckAt, refundedAt);
    }

    private void insertPayment(long reservationId, String status) {
        jdbc.update(insertSql(status, "next_check_at", "now() + interval '10 seconds'"), args(reservationId));
    }

    private void approvedPayment(long reservationId, String pgTxId) {
        jdbc.update(insertSql("APPROVED", "pg_tx_id, approved_at, pg_approved_amount", "'" + pgTxId + "', now(), 50000"),
                args(reservationId));
    }

    // Note: 상태별 필수 컬럼은 호출부가 extraColumns로 넘기고, 나머지 NOT NULL 컬럼은 여기서 채운다.
    private static String insertSql(String status, String extraColumns, String extraValues) {
        return """
                INSERT INTO payments (payment_no, reservation_id, user_id, amount, method, status, pg_order_id,
                                     created_at, updated_at, %s)
                VALUES (?, ?, 1, 50000, 'CARD', '%s', ?, now(), now(), %s)""".formatted(extraColumns, status, extraValues);
    }

    private static Object[] args(long reservationId) {
        return new Object[] {"PAY-" + reservationId + "-" + UUID.randomUUID().toString().substring(0, 8),
                reservationId, UUID.randomUUID()};
    }

    private void insertIdempotencyRecord(long userId, UUID key) {
        jdbc.update("""
                INSERT INTO idempotency_records (user_id, idem_key, request_hash, resource_id, link_type, expires_at, created_at)
                VALUES (?, ?, repeat('a', 64), 1, 'ORIGIN', now() + interval '24 hours', now())""", userId, key);
    }

    private void insertOutbox(UUID eventId) {
        jdbc.update("""
                INSERT INTO outbox_events (event_id, topic, message_key, event_type, payload, status, next_attempt_at, created_at)
                VALUES (?, 'payment.result', '1', 'PaymentApproved', '{}'::jsonb, 'PENDING', now(), now())""", eventId);
    }

    private List<String> names(String sql) {
        return jdbc.queryForList(sql, String.class);
    }

    private static void assertViolation(String constraint, Runnable action) {
        var exception = assertThrows(DataIntegrityViolationException.class, action::run);
        assertTrue(exception.getMessage().contains(constraint), "기대한 제약 " + constraint + ": " + exception.getMessage());
    }
}
