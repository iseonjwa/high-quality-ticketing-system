package com.iseonjwa.payment.scenario;

import com.iseonjwa.payment.pg.application.ApproveCommand;
import com.iseonjwa.payment.pg.application.PgClient;
import com.iseonjwa.payment.pg.application.PgIdempotencyKey;
import com.iseonjwa.payment.pg.application.PgOrderId;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FT-2 PG 응답 유실(failure-scenarios.md 4절). 실제 Mock PG에 응답 유실이나 서버 오류를 걸고, 결제·환불이 {@code UNKNOWN}으로
 * 남았다가 재확인·환불 워커의 조회로 확정되는지 확인한다. 장애는 모두 {@code orderId}·{@code refundId}로 대상을 한정해 건다.
 * 시나리오마다 끝에서 V1~V3를 모두 실행하고, 미해결이 남아 있는 중간 시점에도 한 번 실행한다.
 */
class Ft2PgResponseLossScenarioTest extends ScenarioTestBase {

    @Test
    void a_승인_응답이_유실되면_UNKNOWN으로_두었다가_재확인이_APPROVED로_확정하고_승인은_한_번이다() throws Exception {
        var reservation = newReservation();
        armApproveFault("DROP_RESPONSE");

        var response = approve(reservation, UUID.randomUUID());

        assertEquals(202, response.status(), String.valueOf(response.body()));
        assertEquals("UNKNOWN", response.paymentStatus());
        assertEquals(List.of(), outboxEventTypes(reservation), "확정 전에는 결과 이벤트가 없다");
        // PG는 승인을 저장했지만 우리는 모른다. 명시적 미해결(UNKNOWN)이라 V1의 "PG에만 있는 승인"에서 예외다
        invariants().assertHolds();

        recoverUntilIdle();

        assertEquals("APPROVED", paymentStatus(reservation));
        var order = PG.ledger().payment(pgOrderId(reservation).toString()).orElseThrow();
        assertEquals(1, order.approveCalls(), "재확인은 조회만 하고 승인을 다시 부르지 않는다");
        assertEquals("APPROVED", order.status());
        assertEquals(order.pgTxId(), pgTxId(reservation));
        assertEquals(List.of("PaymentApproved"), outboxEventTypes(reservation));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE message_key = ? AND status = 'SENT'",
                Integer.class, String.valueOf(reservation)));
        invariants().assertHolds();
    }

    @Test
    void b_환불_응답이_유실되면_UNKNOWN으로_두었다가_findRefund로_REFUNDED가_되고_PG_환불은_한_번이다() throws Exception {
        var reservation = approvedWithRefundRequest();
        var refundId = pgRefundId(reservation);
        PG.applyFault("REFUND", "DROP_RESPONSE", 1, null, refundId.toString());

        recoverOneRound();

        assertEquals("UNKNOWN", refundStatus(reservation));
        var stored = PG.ledger().refunds(pgTxId(reservation));
        assertEquals(1, stored.refundCount(), "PG는 환불을 저장했고 응답만 사라졌다");
        assertEquals(1, stored.refunds().get(0).refundCalls());
        invariants().assertHolds();

        recoverUntilIdle();

        assertEquals("REFUNDED", refundStatus(reservation));
        assertEquals("REFUNDED", paymentStatus(reservation));
        var refunds = PG.ledger().refunds(pgTxId(reservation));
        assertEquals(1, refunds.refundCount());
        assertEquals(refundId.toString(), refunds.refunds().get(0).refundId());
        assertEquals(1, refunds.refunds().get(0).refundCalls(), "조회로 확정하고 환불을 다시 부르지 않는다");
        assertEquals(1, refunds.refunds().get(0).refundLookupCalls());
        assertEquals(List.of("PaymentApproved", "PaymentRefunded"), outboxEventTypes(reservation));
        invariants().assertHolds();
    }

    @Test
    void c_환불이_PG에_닿지_않으면_NotFound_뒤_같은_pg_refund_id로_다시_부르고_PG_환불은_한_번이다() throws Exception {
        var reservation = approvedWithRefundRequest();
        var firstRefundId = pgRefundId(reservation);
        PG.applyFault("REFUND", "SERVER_ERROR", 1, null, firstRefundId.toString());

        recoverOneRound();

        assertEquals("UNKNOWN", refundStatus(reservation));
        assertEquals(0, PG.ledger().refunds(pgTxId(reservation)).refundCount(), "서버 오류는 환불을 저장하지 않는다");
        invariants().assertHolds();

        recoverUntilIdle();

        assertEquals("REFUNDED", refundStatus(reservation));
        assertEquals("REFUNDED", paymentStatus(reservation));
        assertEquals(firstRefundId, pgRefundId(reservation), "다시 부를 때 새 환불 ID를 만들지 않는다");
        var refunds = PG.ledger().refunds(pgTxId(reservation));
        assertEquals(1, refunds.refundCount());
        assertEquals(firstRefundId.toString(), refunds.refunds().get(0).refundId(), "PG 원장의 환불 ID가 처음 것과 같다");
        assertEquals(2, refunds.refunds().get(0).refundCalls(), "실패한 첫 호출과 같은 ID의 재호출");
        assertEquals(1, refunds.refunds().get(0).refundLookupCalls());
        assertEquals(1, refunds.refunds().get(0).distinctIdempotencyKeys(), "두 호출이 같은 멱등키를 보냈다");
        invariants().assertHolds();
    }

    @Test
    void d_승인이_PG에_닿지_않으면_NotFound_뒤_늦은_승인을_막고_DECLINED_NOT_SENT가_된다() throws Exception {
        var reservation = newReservation();
        var key = UUID.randomUUID();
        armApproveFault("SERVER_ERROR");

        var response = approve(reservation, key);

        assertEquals(202, response.status(), String.valueOf(response.body()));
        assertEquals("UNKNOWN", response.paymentStatus());
        var orderId = pgOrderId(reservation);
        assertEquals("NONE", PG.ledger().payment(orderId.toString()).orElseThrow().status(), "승인이 PG에 닿지 않았다");
        invariants().assertHolds();

        recoverUntilIdle();

        assertEquals("DECLINED", paymentStatus(reservation));
        assertEquals("NOT_SENT", jdbc.queryForObject("SELECT decline_code FROM payments WHERE reservation_id = ?",
                String.class, reservation));
        assertEquals(List.of("PaymentDeclined"), outboxEventTypes(reservation));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE message_key = ? AND status = 'SENT'",
                Integer.class, String.valueOf(reservation)));
        var order = PG.ledger().payment(orderId.toString()).orElseThrow();
        assertEquals("CANCELED", order.status(), "늦은 승인 방지로 PG가 이 주문을 취소 상태로 막았다");
        assertEquals(1, order.approveCalls());
        // V1~V3는 승인 호출이 1회인 상태에서 확인한다. 아래 늦은 승인 시도는 호출 수를 늘리므로 그 뒤에는 실행하지 않는다
        invariants().assertHolds();

        // 같은 주문으로 늦게 승인을 시도해도 PG가 승인하지 않는다
        var late = pg.approve(new ApproveCommand(new PgOrderId(orderId), BigDecimal.valueOf(AMOUNT), "KRW",
                PgIdempotencyKey.ofApproval(new PgOrderId(orderId)), null));
        assertFalse(late instanceof PgClient.Approved, "늦은 승인을 PG가 받았다: " + late);
        assertEquals("CANCELED", PG.ledger().payment(orderId.toString()).orElseThrow().status());
        assertEquals(0, PG.ledger().summary().approvedCount());
        assertTrue(PG.ledger().approvedOrders().isEmpty());
    }

    // 실제 승인 API로 APPROVED 결제를 만들고 RefundRequested를 소비해 환불 REQUESTED까지 만든다. 워커는 아직 돌리지 않는다
    private long approvedWithRefundRequest() throws Exception {
        var reservation = newReservation();
        var approval = approve(reservation, UUID.randomUUID());
        assertEquals("APPROVED", approval.paymentStatus());
        commandListener.onMessage(refundRequested(newEventId(), reservation, approval.paymentId()));
        assertEquals("REQUESTED", refundStatus(reservation));
        return reservation;
    }
}
