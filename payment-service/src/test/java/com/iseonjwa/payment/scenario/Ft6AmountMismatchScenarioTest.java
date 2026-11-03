package com.iseonjwa.payment.scenario;

import com.iseonjwa.payment.ops.application.OpsRunner;
import com.iseonjwa.payment.ops.application.PaymentResolveCommand;
import com.iseonjwa.payment.pg.application.ApproveCommand;
import com.iseonjwa.payment.pg.application.PgClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

/**
 * FT-6 금액 불일치(INV-3, N16). PG가 승인한 금액이 결제 금액과 다를 때 결제를 승인으로 닫지 않고 운영자 확인으로 넘기며, 운영자가
 * Ops {@code payment resolve}로 PG가 승인한 금액 전액을 환불하는 데까지 확인한다. 끝에서 V1~V3를 모두 실행한다.
 *
 * <p>Note: 변조형 장애({@code WRONG_AMOUNT}, ISE-130)가 아직 없어서 승인 호출 경계에서 금액을 1,000원 줄인다. 결제 서비스가 만든
 * 승인 명령의 {@code orderId}·멱등키는 그대로 두고 금액만 바꿔 실제 Mock PG에 보내므로, PG 원장에는 그 금액으로 승인이 저장되고
 * 앱은 PG가 저장한 다른 금액을 응답으로 받는다. 같은 주문을 다른 금액으로 먼저 승인해 두는 방식은 승인 호출이 주문당 2회가 되어
 * V1("주문당 승인 호출 ≤ 1")을 어기므로 쓰지 않았다.
 */
class Ft6AmountMismatchScenarioTest extends ScenarioTestBase {
    private static final long PG_AMOUNT = AMOUNT - 1_000;

    private ScenarioOps ops;

    @BeforeEach
    void console() {
        ops = new ScenarioOps(webApplicationContext);
    }

    @Test
    void PG가_다른_금액으로_승인하면_NMC이고_Ops_payment_resolve로_PG_승인_금액_전액을_환불해_REFUNDED가_된다() throws Exception {
        var reservation = newReservation();
        armApproveWithPgAmount(PG_AMOUNT);
        var mismatchBefore = approveMismatchCount();

        var response = approve(reservation, UUID.randomUUID());

        // 승인 API 응답: NMC 결제는 409 + Location(api.md 표 A). 승인으로 응답하지 않는다
        assertEquals(409, response.status(), String.valueOf(response.body()));
        assertNotNull(response.location(), "NEEDS_MANUAL_CHECK는 Location을 준다");
        assertEquals("NEEDS_MANUAL_CHECK", paymentStatus(reservation));
        assertEquals("PG_AMOUNT_MISMATCH", jdbc.queryForObject("SELECT reason FROM payment_status_histories "
                + "WHERE target_type = 'PAYMENT' AND payment_id = ? ORDER BY id DESC LIMIT 1", String.class,
                paymentId(reservation)));
        assertEquals(0, new BigDecimal(PG_AMOUNT).compareTo(jdbc.queryForObject(
                "SELECT pg_approved_amount FROM payments WHERE reservation_id = ?", BigDecimal.class, reservation)),
                "결제에는 PG가 승인한 금액이 남는다");
        assertEquals(0, new BigDecimal(AMOUNT).compareTo(jdbc.queryForObject(
                "SELECT amount FROM payments WHERE reservation_id = ?", BigDecimal.class, reservation)));
        assertEquals(List.of(), outboxEventTypes(reservation), "PaymentApproved는 나가지 않는다");
        assertEquals(1.0, approveMismatchCount() - mismatchBefore, "payment.pg.amount.mismatch{operation=approve}가 1 늘었다");
        var order = PG.ledger().payment(pgOrderId(reservation).toString()).orElseThrow();
        assertEquals(1, order.approveCalls());
        // 재확인은 결제가 NMC라 집지 않는다. 사람이 풀 때까지 그대로다
        recoverUntilIdle();
        assertEquals("NEEDS_MANUAL_CHECK", paymentStatus(reservation));
        invariants().assertHolds();

        // 운영자(4-eyes): dry-run은 PG 근거로 환불 시작을 계획하고 상태를 바꾸지 않는다
        var paymentDbId = paymentId(reservation);
        var dryRun = ops.dryRun(PaymentResolveCommand.NAME, "--payment-id=" + paymentDbId);
        assertEquals(OpsRunner.EXIT_OK, dryRun.exit(), dryRun.output());
        assertEquals(0, refundCount(reservation), "dry-run은 환불을 만들지 않는다");
        var applied = ops.applyWithConfirm(PaymentResolveCommand.NAME, dryRun.planHash(), "PG 승인 금액이 달라 환불한다",
                "--payment-id=" + paymentDbId);
        assertEquals(OpsRunner.EXIT_OK, applied.exit(), applied.output());
        assertEquals("NEEDS_MANUAL_CHECK", paymentStatus(reservation), "환불이 끝나기 전까지 결제는 NMC다");
        assertEquals(1, refundCount(reservation));
        assertEquals("REQUESTED", refundStatus(reservation));
        assertEquals(0, new BigDecimal(PG_AMOUNT).compareTo(jdbc.queryForObject("SELECT amount FROM refunds "
                + "WHERE payment_id = ?", BigDecimal.class, paymentDbId)), "환불 금액은 PG가 승인한 금액이다");
        assertEquals("PG_AMOUNT_MISMATCH", jdbc.queryForObject("SELECT reason FROM refunds WHERE payment_id = ?",
                String.class, paymentDbId));
        invariants().assertHolds();

        recoverUntilIdle();

        assertEquals("REFUNDED", refundStatus(reservation));
        assertEquals("REFUNDED", paymentStatus(reservation));
        var refunds = PG.ledger().refunds(pgTxId(reservation));
        assertEquals(1, refunds.refundCount());
        assertEquals(0, new BigDecimal(PG_AMOUNT).compareTo(refunds.refundedTotal()), "PG 승인 금액 전액이 환불됐다");
        assertEquals(1, refunds.refunds().get(0).refundCalls());
        assertEquals(List.of("PaymentRefunded"), outboxEventTypes(reservation), "승인 이벤트 없이 환불 이벤트만 나간다");
        var eventAmount = jdbc.queryForObject("SELECT (payload -> 'payload' ->> 'amount')::numeric FROM outbox_events "
                + "WHERE message_key = ? AND event_type = 'PaymentRefunded'", BigDecimal.class, String.valueOf(reservation));
        assertEquals(0, new BigDecimal(PG_AMOUNT).compareTo(eventAmount), "PaymentRefunded의 금액은 PG 승인 금액이다");
        assertEquals(List.of("APPLIED"), jdbc.queryForList("SELECT result FROM manual_action_logs WHERE command = ? "
                + "AND target_id = ?", String.class, PaymentResolveCommand.NAME, paymentDbId));
        assertTrue(PG.ledger().approvedOrders().size() == 1);
        invariants().assertHolds();
    }

    /** 다음 승인 호출부터 PG에 보내는 금액을 {@code pgAmount}로 바꾼다. 주문 ID와 멱등키는 결제 서비스가 만든 그대로다. */
    private void armApproveWithPgAmount(long pgAmount) {
        // 스파이가 감싼 원본 객체를 바로 부른다. 스파이를 다시 부르면 이 스텁이 자기 자신을 부르게 된다
        var original = (PgClient) Mockito.mockingDetails(pg).getMockCreationSettings().getSpiedInstance();
        doAnswer(invocation -> {
            var command = (ApproveCommand) invocation.getArgument(0);
            return original.approve(new ApproveCommand(command.orderId(), BigDecimal.valueOf(pgAmount), command.currency(),
                    command.idempotencyKey(), command.clientPaymentRef()));
        }).when(pg).approve(any());
    }

    private double approveMismatchCount() {
        var counter = meters.find("payment.pg.amount.mismatch").tag("operation", "approve").counter();
        return counter == null ? 0 : counter.count();
    }
}
