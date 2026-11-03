package com.iseonjwa.payment.scenario;

import com.iseonjwa.payment.ops.application.OpsRunner;
import com.iseonjwa.payment.ops.application.RefundResolveCommand;
import com.iseonjwa.payment.ops.application.RefundRetryCommand;
import com.iseonjwa.payment.pg.application.PgClient;
import com.iseonjwa.payment.pg.application.RefundRejectReason;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * FT-5 환불 중단(failure-scenarios.md 4절). 환불이 PG 쪽 사정으로 멈추는 네 경우를 확인한다. 장애는 모두 {@code refundId}로 한정해
 * 건다. 운영자 복구는 Ops 명령을 4-eyes 절차(dry-run → 계획 해시 → apply)대로 부른다. 시나리오마다 끝에서 V1~V3를 모두 실행한다.
 *
 * <p>Note: (c)는 PG 거절 뒤의 조회 결과를 {@code PgClient} 스파이로 정한다. Mock PG는 거절한 환불을 저장하지 않아 그 환불 ID를 조회하면
 * 항상 {@code NotFound}다. 그래서 실제 Mock PG만으로는 {@code refund resolve --close-failed}의 종결 분기에 닿지 못한다. 거절 환불을
 * 조회로 알려 주는 PG를 가정해 {@code findRefund} 응답만 {@code RefundRejected}로 대신하고, 환불 거절 자체는 실제 Mock PG가 낸다.
 * 실제 Mock PG 그대로의 결과(NotFound로 거부)는 (d)가 확인한다.
 *
 * <p>Note: V3는 환불 {@code FAILED}를 정상 종결로 본다. {@code FAILED}는 운영자가 PG로는 환불할 수 없음을 확인하고 외부 조치로 넘긴
 * 종결 상태이고, 결제는 {@code APPROVED}로 남는다(domain-and-states.md 2절). 환불 행이 있어 대사 RC-2의 대상도 아니다.
 */
class Ft5RefundInterruptionScenarioTest extends ScenarioTestBase {
    private ScenarioOps ops;

    @BeforeEach
    void console() {
        ops = new ScenarioOps(webApplicationContext);
    }

    @Test
    void a_pg_recovery_CB가_OPEN인_동안_워커는_PG를_부르지_않고_시도_횟수가_그대로이며_닫힌_뒤_완료한다() throws Exception {
        var reservation = approvedPaymentWithRefundRequest();
        recoveryGate.circuitBreaker().transitionToOpenState();

        for (var round = 0; round < 5; round++) {
            advanceTime();
            assertFalse(refundWorker.processNext(), "허가를 못 얻으면 이번 주기는 끝난다");
        }

        assertEquals("REQUESTED", refundStatus(reservation));
        assertEquals(0, refundAttempt(reservation), "CB가 OPEN인 동안은 시도 횟수를 세지 않는다");
        assertEquals(null, jdbc.queryForObject("SELECT pg_requested_at FROM refunds WHERE pg_refund_id = ?",
                java.sql.Timestamp.class, pgRefundId(reservation)), "PG에 요청을 보낸 적이 없다");
        verify(pg, never()).refund(any());
        verify(pg, never()).findRefund(any(), any());
        assertEquals(0, PG.ledger().refunds(pgTxId(reservation)).refundCount());
        // 그룹이 나뉘어 있어 복구 CB가 열려 있어도 신규 승인은 진행된다(N25)
        var other = newReservation();
        var otherApproval = approve(other, UUID.randomUUID());
        assertEquals(200, otherApproval.status(), String.valueOf(otherApproval.body()));
        assertEquals("APPROVED", otherApproval.paymentStatus());
        invariants().assertHolds();

        recoveryGate.circuitBreaker().reset();
        recoverUntilIdle();

        assertEquals("REFUNDED", refundStatus(reservation));
        assertEquals("REFUNDED", paymentStatus(reservation));
        assertEquals(1, refundAttempt(reservation), "닫힌 뒤 첫 호출 한 번으로 끝났다");
        var refunds = PG.ledger().refunds(pgTxId(reservation));
        assertEquals(1, refunds.refundCount());
        assertEquals(1, refunds.refunds().get(0).refundCalls());
        assertEquals("APPROVED", paymentStatus(other));
        invariants().assertHolds();
    }

    @Test
    void b_장기_장애로_9회_뒤_NMC가_되고_장애를_풀고_refund_retry_뒤_워커가_완료하며_PG_환불은_한_번이다() throws Exception {
        var reservation = approvedPaymentWithRefundRequest();
        var refundId = pgRefundId(reservation);
        PG.applyFault("REFUND", "SERVER_ERROR", null, null, refundId.toString());
        PG.applyFault("REFUND_LOOKUP", "SERVER_ERROR", null, null, refundId.toString());

        recoverUntilIdle();

        assertEquals("NEEDS_MANUAL_CHECK", refundStatus(reservation));
        assertEquals(9, refundAttempt(reservation), "9번째 호출까지 PG를 불렀다");
        assertEquals("REFUND_UNRESOLVED", lastRefundHistoryReason(reservation));
        assertEquals("APPROVED", paymentStatus(reservation), "환불이 끝나지 않았으니 결제는 그대로다");
        assertEquals(0, PG.ledger().refunds(pgTxId(reservation)).refundCount(), "PG는 환불을 저장하지 않았다");
        // 알림 대상(NMC 환불)이 지표에 잡히는지는 V3가 Gauge로 확인한다
        invariants().assertHolds();

        // 장애가 풀린 뒤 운영자가 4-eyes로 재시도를 건다
        PG.applyFault("REFUND", "NONE", null, null, refundId.toString());
        PG.applyFault("REFUND_LOOKUP", "NONE", null, null, refundId.toString());
        var refundDbId = refundDbId(reservation);
        var dryRun = ops.dryRun(RefundRetryCommand.NAME, "--refund-id=" + refundDbId);
        assertEquals(OpsRunner.EXIT_OK, dryRun.exit(), dryRun.output());
        assertEquals("NEEDS_MANUAL_CHECK", refundStatus(reservation), "dry-run은 상태를 바꾸지 않는다");
        var applied = ops.applyWithConfirm(RefundRetryCommand.NAME, dryRun.planHash(), "PG 환불 장애가 풀렸다",
                "--refund-id=" + refundDbId);
        assertEquals(OpsRunner.EXIT_OK, applied.exit(), applied.output());
        assertEquals("REQUESTED", refundStatus(reservation));
        assertEquals("OPS_RETRY", lastRefundHistoryReason(reservation));
        assertEquals(List.of("APPLIED"), manualActionResults(RefundRetryCommand.NAME));

        recoverUntilIdle();

        assertEquals("REFUNDED", refundStatus(reservation));
        assertEquals("REFUNDED", paymentStatus(reservation));
        assertEquals(refundId, pgRefundId(reservation), "재시도도 같은 pg_refund_id를 쓴다");
        var refunds = PG.ledger().refunds(pgTxId(reservation));
        assertEquals(1, refunds.refundCount());
        assertEquals(refundId.toString(), refunds.refunds().get(0).refundId());
        assertEquals(List.of("PaymentApproved", "PaymentRefunded"), outboxEventTypes(reservation));
        invariants().assertHolds();
    }

    @Test
    void c_PG가_환불을_거절하면_NMC이고_close_failed는_외부_조치_참조가_있어야만_FAILED로_종결한다() throws Exception {
        var reservation = approvedPaymentWithRefundRequest();
        var refundId = pgRefundId(reservation);
        PG.applyFault("REFUND", "REJECT", null, null, refundId.toString());

        recoverUntilIdle();

        assertEquals("NEEDS_MANUAL_CHECK", refundStatus(reservation), "거절은 FAILED가 아니라 운영자 확인이다");
        assertEquals("REFUND_REJECTED", lastRefundHistoryReason(reservation));
        assertEquals("APPROVED", paymentStatus(reservation));
        assertEquals(0, PG.ledger().refunds(pgTxId(reservation)).refundCount());
        invariants().assertHolds();

        // 이 PG는 거절한 환불을 조회로도 거절이라고 알려 준다(클래스 설명 참고)
        doReturn(new PgClient.RefundRejected(RefundRejectReason.NOT_REFUNDABLE)).when(pg).findRefund(any(), any());
        var refundDbId = refundDbId(reservation);

        // 외부 조치 참조 없이는 종결할 수 없다. dry-run도 적용도 거부된다
        var withoutRef = ops.dryRun(RefundResolveCommand.NAME, "--refund-id=" + refundDbId, "--close-failed");
        assertEquals(OpsRunner.EXIT_REJECTED, withoutRef.exit(), withoutRef.output());
        var forcedApply = ops.applyWithConfirm(RefundResolveCommand.NAME, "0".repeat(64), "참조 없이 종결 시도",
                "--refund-id=" + refundDbId, "--close-failed");
        assertEquals(OpsRunner.EXIT_REJECTED, forcedApply.exit(), forcedApply.output());
        assertTrue(forcedApply.output().contains("--external-ref"), forcedApply.output());
        assertEquals("NEEDS_MANUAL_CHECK", refundStatus(reservation));
        assertEquals(List.of("REJECTED"), manualActionResults(RefundResolveCommand.NAME));

        // 참조를 함께 주면 FAILED로 닫힌다. 결제는 APPROVED로 남고 이벤트는 나가지 않는다
        var applied = ops.dryRunThenApply(RefundResolveCommand.NAME, "고객 계좌로 직접 환불했다",
                "--refund-id=" + refundDbId, "--close-failed", "--external-ref=BANK-REFUND-0001");
        assertEquals(OpsRunner.EXIT_OK, applied.exit(), applied.output());
        assertEquals("FAILED", refundStatus(reservation));
        assertEquals("OPS_CLOSED", lastRefundHistoryReason(reservation));
        assertEquals("APPROVED", paymentStatus(reservation), "돈이 PG로 돌아가지 않았으므로 결제는 APPROVED다");
        assertEquals(List.of("PaymentApproved"), outboxEventTypes(reservation), "FAILED 종결은 이벤트를 내지 않는다");
        assertEquals(List.of("REJECTED", "APPLIED"), manualActionResults(RefundResolveCommand.NAME));
        assertEquals("BANK-REFUND-0001", jdbc.queryForObject("SELECT external_ref FROM manual_action_logs "
                + "WHERE command = ? AND result = 'APPLIED'", String.class, RefundResolveCommand.NAME));
        // 운영자 종결(FAILED)은 정상 종결로 본다. V3가 통과해야 한다
        assertEquals(List.of(), invariants().v3());
        invariants().assertHolds();
    }

    @Test
    void d_콘솔_환불은_우리_pg_refund_id가_없어_refund_resolve가_NotFound로_거부하고_NMC로_남는다() throws Exception {
        var reservation = approvedPaymentWithRefundRequest();
        var refundId = pgRefundId(reservation);
        // 환불을 NMC로 만든다. PG 콘솔에서 우리 pg_refund_id 없이 환불된 상태는 Mock PG로 만들 수 없다(PG 원장에 환불이 없다)
        PG.applyFault("REFUND", "REJECT", null, null, refundId.toString());
        recoverUntilIdle();
        assertEquals("NEEDS_MANUAL_CHECK", refundStatus(reservation));
        var refundDbId = refundDbId(reservation);

        var dryRun = ops.dryRun(RefundResolveCommand.NAME, "--refund-id=" + refundDbId);
        var applied = ops.applyWithConfirm(RefundResolveCommand.NAME, "0".repeat(64), "콘솔에서 환불했다",
                "--refund-id=" + refundDbId);

        assertEquals(OpsRunner.EXIT_REJECTED, dryRun.exit(), dryRun.output());
        assertTrue(dryRun.output().contains("PG에 환불 기록이 없습니다"), dryRun.output());
        assertTrue(dryRun.output().contains("manual-recovery.md 8절"), dryRun.output());
        assertEquals(OpsRunner.EXIT_REJECTED, applied.exit(), applied.output());
        assertEquals("NEEDS_MANUAL_CHECK", refundStatus(reservation), "확인하지 못하면 상태를 바꾸지 않는다");
        assertEquals("APPROVED", paymentStatus(reservation));
        assertNotEquals("REFUNDED", refundStatus(reservation));
        assertEquals(List.of("REJECTED"), manualActionResults(RefundResolveCommand.NAME));
        assertEquals(0, PG.ledger().refunds(pgTxId(reservation)).refundCount());
        invariants().assertHolds();
    }

    private int refundAttempt(long reservation) {
        return jdbc.queryForObject("SELECT attempt FROM refunds WHERE payment_id IN "
                + "(SELECT id FROM payments WHERE reservation_id = ?)", Integer.class, reservation);
    }

    private long refundDbId(long reservation) {
        return jdbc.queryForObject("SELECT id FROM refunds WHERE payment_id IN "
                + "(SELECT id FROM payments WHERE reservation_id = ?)", Long.class, reservation);
    }

    private String lastRefundHistoryReason(long reservation) {
        return jdbc.queryForObject("SELECT reason FROM payment_status_histories WHERE target_type = 'REFUND' "
                + "AND payment_id IN (SELECT id FROM payments WHERE reservation_id = ?) ORDER BY id DESC LIMIT 1",
                String.class, reservation);
    }

    private List<String> manualActionResults(String command) {
        return jdbc.queryForList("SELECT result FROM manual_action_logs WHERE command = ? AND target_id IN "
                + "(SELECT id FROM refunds WHERE payment_id IN (SELECT id FROM payments WHERE reservation_id BETWEEN ? AND ?)) "
                + "ORDER BY id", String.class, command, FIRST_RESERVATION, LAST_RESERVATION);
    }
}
