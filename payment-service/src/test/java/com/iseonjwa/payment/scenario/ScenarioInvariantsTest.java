package com.iseonjwa.payment.scenario;

import com.iseonjwa.payment.pg.application.ApproveCommand;
import com.iseonjwa.payment.pg.application.PgClient;
import com.iseonjwa.payment.pg.application.PgIdempotencyKey;
import com.iseonjwa.payment.pg.application.PgOrderId;
import com.iseonjwa.payment.pg.application.PgPaymentRef;
import com.iseonjwa.payment.pg.application.PgRefundId;
import com.iseonjwa.payment.pg.application.RefundCommand;
import com.iseonjwa.payment.payment.application.PaymentResultPublisher;
import com.iseonjwa.payment.payment.domain.PaymentRepository;
import com.iseonjwa.payment.payment.domain.PaymentStatus;
import com.iseonjwa.payment.payment.domain.PaymentStatusHistory;
import com.iseonjwa.payment.payment.domain.PaymentStatusHistoryRepository;
import com.iseonjwa.payment.payment.domain.StatusActor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 검증기({@link ScenarioInvariants}) 자체의 테스트. 일부러 위반 데이터를 만들어 V1·V2·V3가 각각 실패하는지, 위반이 없으면
 * 통과하는지 확인한다. 검증기가 아무것도 못 잡는 채로 시나리오가 통과하는 일을 막으려는 것이다.
 *
 * <p>Note: 결제 DB의 UNIQUE(예매당 결제 1건, 결제당 환불 1건)는 DB가 먼저 막으므로 그 위반은 만들 수 없다. 그래서 중복 위반은
 * Mock PG 원장 쪽(승인 호출 수, 환불 수)으로 만든다.
 */
class ScenarioInvariantsTest extends ScenarioTestBase {
    private static final BigDecimal AMOUNT_VALUE = BigDecimal.valueOf(AMOUNT);

    @Test
    void 위반이_없으면_V1_V2_V3_모두_통과한다() {
        var reservation = newReservation();
        var approved = pgApprove(UUID.randomUUID());
        var paymentId = insertPayment(reservation, "APPROVED", approved.orderId, approved.pgTxId);
        recordApprovalAsProduction(paymentId);

        invariants().assertHolds();
    }

    @Test
    void V1_같은_주문의_PG_승인_호출이_둘이면_approveCalls로_잡는다() {
        var reservation = newReservation();
        var orderId = UUID.randomUUID();
        var approved = pgApprove(orderId);
        pgApprove(orderId);
        insertPayment(reservation, "APPROVED", orderId, approved.pgTxId);

        var violations = invariants().v1();

        assertEquals(1, violations.size(), violations.toString());
        assertTrue(violations.get(0).contains("approveCalls=2"), violations.get(0));
    }

    @Test
    void V1_PG만_승인한_주문은_결제가_없으면_PG에만_있는_승인으로_잡는다() {
        var approved = pgApprove(UUID.randomUUID());

        var violations = invariants().v1();

        assertEquals(1, violations.size(), violations.toString());
        assertTrue(violations.get(0).contains("PG에만 있는 승인") && violations.get(0).contains(approved.pgTxId), violations.get(0));
    }

    @Test
    void V1_PG가_승인했는데_결제가_DECLINED로_닫혀_있으면_PG에만_있는_승인이다() {
        var reservation = newReservation();
        var approved = pgApprove(UUID.randomUUID());
        insertPayment(reservation, "DECLINED", approved.orderId, null);

        var violations = invariants().v1();

        assertEquals(1, violations.size(), violations.toString());
        assertTrue(violations.get(0).contains("PG에만 있는 승인") && violations.get(0).contains("DECLINED"), violations.get(0));
    }

    @Test
    void V1_PG가_승인했지만_결제가_명시적_미해결_UNKNOWN이고_거래_식별자가_없으면_예외로_둔다() {
        var reservation = newReservation();
        var approved = pgApprove(UUID.randomUUID());
        insertPayment(reservation, "UNKNOWN", approved.orderId, null);

        assertEquals(0, invariants().v1().size());
    }

    @Test
    void V2_PG_환불이_둘이면_refundCount로_잡는다() {
        var reservation = newReservation();
        var approved = pgApprove(UUID.randomUUID());
        insertPayment(reservation, "APPROVED", approved.orderId, approved.pgTxId);
        // 부분 환불 둘: Mock PG는 합계가 승인 금액 이하이면 서로 다른 환불 ID를 모두 받는다
        pgRefund(approved.pgTxId, BigDecimal.valueOf(20000));
        pgRefund(approved.pgTxId, BigDecimal.valueOf(20000));

        var violations = invariants().v2();

        assertEquals(1, violations.size(), violations.toString());
        assertTrue(violations.get(0).contains("refundCount") && violations.get(0).contains("2건"), violations.get(0));
    }

    @Test
    void V3_REQUESTED로_남은_결제는_정상_종결도_명시적_미해결도_아니다() {
        var reservation = newReservation();
        insertPayment(reservation, "REQUESTED", UUID.randomUUID(), null);

        var violations = invariants().v3();

        assertEquals(1, violations.size(), violations.toString());
        assertTrue(violations.get(0).contains("status=REQUESTED"), violations.get(0));
    }

    @Test
    void V3_명시적_미해결은_통과하고_Gauge가_0보다_크다() {
        insertPayment(newReservation(), "UNKNOWN", UUID.randomUUID(), null);
        insertPayment(newReservation(), "NEEDS_MANUAL_CHECK", UUID.randomUUID(), null);
        insertRefund(approvedPayment(), "REQUESTED");
        insertRefund(approvedPayment(), "NEEDS_MANUAL_CHECK");
        jdbc.update("""
                INSERT INTO dead_letters (source_topic, source_partition, source_offset, event_key, payload_raw, reason,
                                          status, created_at)
                VALUES ('payment.command', ?, 1, ?, '{}', '시나리오 검증기 테스트', 'OPEN', now())""",
                DLQ_PARTITION, UUID.randomUUID().toString());

        assertEquals(0, invariants().v3().size());
    }

    @Test
    void 위반이_여럿이면_한_번에_모아_보여_준다() {
        pgApprove(UUID.randomUUID());
        insertPayment(newReservation(), "REQUESTED", UUID.randomUUID(), null);

        var failure = assertThrows(AssertionError.class, () -> invariants().assertHolds());

        assertTrue(failure.getMessage().contains("V1 PG에만 있는 승인"), failure.getMessage());
        assertTrue(failure.getMessage().contains("V3 결제"), failure.getMessage());
        assertTrue(failure.getMessage().contains("2건"), failure.getMessage());
    }

    // ----- 데이터 -----

    @Autowired
    PaymentRepository payments;
    @Autowired
    PaymentStatusHistoryRepository histories;
    @Autowired
    PaymentResultPublisher resultPublisher;
    @Autowired
    PlatformTransactionManager transactionManager;

    /**
     * 승인 확정이 운영에서 남기는 이력과 Outbox 행을 같은 트랜잭션으로 쓴다({@code PaymentTransitionService.approve}와 같은
     * 순서). 이력은 {@code PaymentStatusHistory.of}, Outbox는 {@code PaymentResultPublisher}를 그대로 불러 봉투 모양을
     * 운영과 같게 둔다. 직접 짠 JSON이 운영 봉투와 다르면 RC-8 대응을 잘못 통과시킬 수 있어서다.
     */
    private void recordApprovalAsProduction(long paymentId) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            var payment = payments.findById(paymentId).orElseThrow();
            histories.save(PaymentStatusHistory.of(paymentId, PaymentStatus.REQUESTED, PaymentStatus.APPROVED, null,
                    StatusActor.SYSTEM, Instant.now()));
            resultPublisher.publishApproved(payment, Instant.now());
        });
    }

    private record PgApproval(UUID orderId, String pgTxId) {
    }

    private PgApproval pgApprove(UUID orderId) {
        var order = new PgOrderId(orderId);
        var approved = assertInstanceOf(PgClient.Approved.class, pg.approve(new ApproveCommand(order, AMOUNT_VALUE, "KRW",
                PgIdempotencyKey.ofApproval(order), null)));
        return new PgApproval(orderId, approved.paymentRef().value());
    }

    private void pgRefund(String pgTxId, BigDecimal amount) {
        var refundId = new PgRefundId(UUID.randomUUID());
        assertInstanceOf(PgClient.Refunded.class, pg.refund(new RefundCommand(new PgPaymentRef(pgTxId), refundId, amount,
                "KRW", "CANCELED", PgIdempotencyKey.ofRefund(refundId))));
    }

    private long approvedPayment() {
        var reservation = newReservation();
        var approved = pgApprove(UUID.randomUUID());
        return insertPayment(reservation, "APPROVED", approved.orderId, approved.pgTxId);
    }

    private long insertPayment(long reservation, String status, UUID orderId, String pgTxId) {
        var approved = status.equals("APPROVED");
        jdbc.update("""
                INSERT INTO payments (payment_no, reservation_id, user_id, amount, method, status, pg_order_id, pg_provider, pg_tx_id,
                                     pg_approved_amount, decline_code, next_check_at, approved_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'CARD', ?, ?, 'MOCK', ?, ?, ?, ?, ?, now(), now())""",
                "PAY-S" + reservation, reservation, USER, AMOUNT, status, orderId, pgTxId,
                approved ? AMOUNT : null, status.equals("DECLINED") ? "NOT_SENT" : null,
                // 재확인이 집지 못하게 먼 미래로 둔다. 이 검증기 테스트는 서비스를 돌리지 않는다
                status.equals("UNKNOWN") || status.equals("REQUESTED")
                        ? java.sql.Timestamp.from(java.time.Instant.now().plusSeconds(86_400)) : null,
                approved ? java.sql.Timestamp.from(java.time.Instant.now()) : null);
        return paymentId(reservation);
    }

    private void insertRefund(long paymentId, String status) {
        jdbc.update("""
                INSERT INTO refunds (payment_id, pg_refund_id, amount, reason, source, status, next_check_at, created_at,
                                     updated_at)
                VALUES (?, ?, ?, 'CANCELED', 'EVENT', ?, ?, now(), now())""",
                paymentId, UUID.randomUUID(), AMOUNT, status,
                status.equals("REQUESTED") ? java.sql.Timestamp.from(java.time.Instant.now().plusSeconds(86_400)) : null);
    }
}
