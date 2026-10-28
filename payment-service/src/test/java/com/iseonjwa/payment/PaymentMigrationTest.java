package com.iseonjwa.payment;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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
        var constraints = names("SELECT conname FROM pg_constraint WHERE conrelid = 'payment'::regclass");
        assertTrue(constraints.containsAll(List.of(
                "uq_payment_payment_no", "uq_payment_reservation_id", "uq_payment_pg_order_id", "uq_payment_pg_tx_id",
                "chk_payment_amount", "chk_payment_currency", "chk_payment_method", "chk_payment_status",
                "chk_payment_check_schedule", "chk_payment_approved", "chk_payment_refunded", "chk_payment_declined")),
                "누락된 제약이 있다: " + constraints);
        var indexes = names("SELECT indexname FROM pg_indexes WHERE tablename = 'payment'");
        assertTrue(indexes.containsAll(List.of("idx_payment_next_check_at", "idx_payment_unsettled_created_at")));
    }

    @Test
    void 예매_하나에_결제_행을_둘_만들_수_없다() {
        var reservationId = 1001L;
        insertPayment(reservationId, "REQUESTED");
        assertViolation("uq_payment_reservation_id", () -> insertPayment(reservationId, "REQUESTED"));
    }

    @Test
    void 거절된_예매에도_두_번째_결제_행을_만들_수_없다() {
        var reservationId = 1002L;
        jdbc.update(insertSql("DECLINED", "decline_code", "'CARD_REJECTED'"), args(reservationId));
        assertViolation("uq_payment_reservation_id", () -> insertPayment(reservationId, "REQUESTED"));
    }

    @Test
    void 같은_PG_거래를_두_결제에_연결할_수_없다() {
        approvedPayment(1003L, "pg-tx-duplicated");
        assertViolation("uq_payment_pg_tx_id", () -> approvedPayment(1004L, "pg-tx-duplicated"));
    }

    @Test
    void 승인_금액이_결제_금액과_다르면_APPROVED로_저장할_수_없다() {
        assertViolation("chk_payment_approved", () -> jdbc.update(
                insertSql("APPROVED", "pg_tx_id, approved_at, pg_approved_amount", "'pg-tx-1', now(), 49000"),
                args(1005L)));
    }

    @Test
    void PG_거래_식별자_없이_APPROVED로_저장할_수_없다() {
        assertViolation("chk_payment_approved", () -> jdbc.update(
                insertSql("APPROVED", "approved_at, pg_approved_amount", "now(), 50000"), args(1006L)));
    }

    @Test
    void 다음_조회_시각_없이_REQUESTED로_저장할_수_없다() {
        assertViolation("chk_payment_check_schedule", () -> jdbc.update(
                insertSql("REQUESTED", "next_check_at", "NULL"), args(1007L)));
    }

    @Test
    void 사유_없이_DECLINED로_저장할_수_없다() {
        assertViolation("chk_payment_declined", () -> jdbc.update(
                insertSql("DECLINED", "decline_code", "NULL"), args(1008L)));
    }

    @Test
    void 환불_완료_시각_없이_REFUNDED로_저장할_수_없다() {
        assertViolation("chk_payment_refunded", () -> jdbc.update(
                insertSql("REFUNDED", "pg_tx_id, refunded_at", "'pg-tx-2', NULL"), args(1009L)));
    }

    @Test
    void 원화가_아닌_통화를_거절한다() {
        assertViolation("chk_payment_currency", () -> jdbc.update(
                insertSql("REQUESTED", "next_check_at, currency", "now(), 'USD'"), args(1010L)));
    }

    @Test
    void 금액이_0원_이하인_결제를_거절한다() {
        assertViolation("chk_payment_amount", () -> jdbc.update(
                insertSql("REQUESTED", "next_check_at", "now()").replace("50000", "0"), args(1011L)));
    }

    @Test
    void 이력_테이블은_대상_종류를_제한하고_존재하지_않는_결제를_참조할_수_없다() {
        var message = assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("""
                INSERT INTO payment_status_history (payment_id, target_type, to_status, actor, created_at)
                VALUES (999999999, 'PAYMENT', 'REQUESTED', 'SYSTEM', now())""")).getMessage();
        assertTrue(message.contains("payment_status_history_payment_id_fkey"), message);
    }

    @Test
    void 멱등_기록은_사용자와_키를_합친_기본키를_쓴다() {
        var key = UUID.randomUUID();
        insertIdempotencyRecord(7L, key);
        assertViolation("idempotency_record_pkey", () -> insertIdempotencyRecord(7L, key));
    }

    @Test
    void 같은_멱등키라도_다른_사용자는_따로_기록한다() {
        var key = UUID.randomUUID();
        insertIdempotencyRecord(7L, key);
        insertIdempotencyRecord(8L, key);
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM idempotency_record WHERE idem_key = ?", Integer.class, key));
    }

    @Test
    void 같은_eventId는_Outbox에_두_번_들어갈_수_없다() {
        var eventId = UUID.randomUUID();
        insertOutbox(eventId);
        assertViolation("uq_outbox_event_id", () -> insertOutbox(eventId));
    }

    @Test
    void 같은_이벤트도_소비_그룹이_다르면_각각_처리한_것으로_기록한다() {
        var eventId = UUID.randomUUID();
        jdbc.update("INSERT INTO processed_event VALUES (?, 'group-a', now())", eventId);
        jdbc.update("INSERT INTO processed_event VALUES (?, 'group-b', now())", eventId);
        assertViolation("processed_event_pkey",
                () -> jdbc.update("INSERT INTO processed_event VALUES (?, 'group-a', now())", eventId));
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
                INSERT INTO payment (payment_no, reservation_id, user_id, amount, method, status, pg_order_id,
                                     created_at, updated_at, %s)
                VALUES (?, ?, 1, 50000, 'CARD', '%s', ?, now(), now(), %s)""".formatted(extraColumns, status, extraValues);
    }

    private static Object[] args(long reservationId) {
        return new Object[] {"PAY-" + reservationId + "-" + UUID.randomUUID().toString().substring(0, 8),
                reservationId, UUID.randomUUID()};
    }

    private void insertIdempotencyRecord(long userId, UUID key) {
        jdbc.update("""
                INSERT INTO idempotency_record (user_id, idem_key, request_hash, resource_id, link_type, expires_at, created_at)
                VALUES (?, ?, repeat('a', 64), 1, 'ORIGIN', now() + interval '24 hours', now())""", userId, key);
    }

    private void insertOutbox(UUID eventId) {
        jdbc.update("""
                INSERT INTO outbox (event_id, topic, message_key, event_type, payload, status, next_attempt_at, created_at)
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
