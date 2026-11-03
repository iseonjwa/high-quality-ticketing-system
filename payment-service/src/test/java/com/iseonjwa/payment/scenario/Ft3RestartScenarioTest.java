package com.iseonjwa.payment.scenario;

import com.iseonjwa.payment.refund.application.RefundRequestService;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static com.iseonjwa.payment.support.PaymentTokenFixtures.token;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

/**
 * FT-3 서버 재시작(failure-scenarios.md 4절). 테스트는 "재시작 직후의 DB·PG 상태"를 만들고 복구 경로를 돌린다. 상태는 운영 코드를
 * 실제로 실행하다 {@link ProcessCrash}(프로세스가 죽는 것을 흉내 내는 {@code Error})로 끊어서 만든다. 운영 코드는 {@code Error}를
 * PG 결과 모름으로 다루지 않고 통과시키므로, 끊긴 지점 뒤의 단계(TX2, 결과 반영, 임대 해제)는 실행되지 않는다.
 * 시나리오마다 끝에서 V1~V3를 모두 실행한다.
 *
 * <p>Note: 실제 프로세스를 강제 종료하는 확인은 테스트로 하지 않는다. compose에서 {@code payment-service}를 {@code kill -9}로
 * 죽이고 다시 띄워 메인이 수동으로 확인한다. 이 테스트가 보장하는 것은 "그 순간의 DB·PG 상태에서 복구 경로가 올바르다"까지다.
 *
 * <p>Note: 죽은 시점의 DB 상태는 이미 커밋된 것만 남는다. TX1 커밋 뒤에는 결제 행과 멱등 기록이 있고, 환불 워커의 claim 뒤에는
 * 임대와 {@code pg_requested_at}이 있다. 임대가 끝나기 전에는 다른 실행이 그 행을 집지 못한다는 것도 같이 본다.
 */
class Ft3RestartScenarioTest extends ScenarioTestBase {
    // DLQ 처리기가 트랜잭션 안에서 환불을 만드는 단계. 여기서 예외를 내 롤백을 만든다
    @MockitoSpyBean
    RefundRequestService refundRequests;

    @Test
    void a_TX1_커밋_뒤_PG_호출_전에_죽으면_PG에_주문이_없어_NotFound_뒤_늦은_승인을_막고_DECLINED_NOT_SENT가_된다() throws Exception {
        var reservation = newReservation();
        var key = UUID.randomUUID();
        var paymentToken = token(USER, reservation, AMOUNT).build();
        armApproveCrash(CrashPoint.BEFORE_PG_CALL);

        approveAndCrash(reservation, key, paymentToken);

        assertEquals("REQUESTED", paymentStatus(reservation), "TX1까지만 실행됐다");
        var orderId = pgOrderId(reservation);
        assertTrue(PG.ledger().payment(orderId.toString()).isEmpty(), "PG는 이 주문을 모른다");
        // 재시작 직후 같은 키로 다시 오면 PG를 다시 부르지 않고 기존 결제로 응답한다(N3)
        var retry = approve(reservation, key, paymentToken);
        assertEquals(202, retry.status(), String.valueOf(retry.body()));
        assertEquals(paymentId(reservation), retry.paymentId());
        assertTrue(PG.ledger().payment(orderId.toString()).isEmpty(), "재시도가 PG를 부르지 않았다");

        recoverUntilIdle();

        assertEquals("DECLINED", paymentStatus(reservation));
        assertEquals("NOT_SENT", jdbc.queryForObject("SELECT decline_code FROM payments WHERE reservation_id = ?",
                String.class, reservation));
        assertEquals(List.of("PaymentDeclined"), outboxEventTypes(reservation));
        var order = PG.ledger().payment(orderId.toString()).orElseThrow();
        assertEquals("CANCELED", order.status(), "늦은 승인 방지로 이 주문은 더 승인되지 않는다");
        assertEquals(0, order.approveCalls());
        // 확정된 뒤 같은 키는 저장된 결과를 돌려주고 PG를 부르지 않는다
        var afterRecovery = approve(reservation, key, paymentToken);
        assertEquals("DECLINED", afterRecovery.paymentStatus());
        assertEquals(0, PG.ledger().payment(orderId.toString()).orElseThrow().approveCalls());
        invariants().assertHolds();
    }

    @Test
    void b_PG가_승인한_뒤_응답을_받기_전에_죽으면_재확인이_APPROVED로_확정하고_승인은_한_번이다() throws Exception {
        var reservation = newReservation();
        var key = UUID.randomUUID();
        var paymentToken = token(USER, reservation, AMOUNT).build();
        armApproveCrash(CrashPoint.AFTER_PG_APPROVAL);

        approveAndCrash(reservation, key, paymentToken);

        assertEquals("REQUESTED", paymentStatus(reservation));
        assertEquals(null, pgTxId(reservation), "앱은 승인 결과를 받지 못했다");
        var orderId = pgOrderId(reservation);
        var order = PG.ledger().payment(orderId.toString()).orElseThrow();
        assertEquals("APPROVED", order.status(), "PG에는 승인이 남았다");
        assertEquals(1, order.approveCalls());
        assertEquals(List.of(), outboxEventTypes(reservation), "확정 전에는 결과 이벤트가 없다");

        recoverUntilIdle();

        assertEquals("APPROVED", paymentStatus(reservation));
        assertEquals(order.pgTxId(), pgTxId(reservation));
        assertEquals(1, PG.ledger().payment(orderId.toString()).orElseThrow().approveCalls(), "재확인은 승인을 다시 부르지 않는다");
        assertEquals(List.of("PaymentApproved"), outboxEventTypes(reservation));
        // 재시작 뒤 같은 키 재시도(N3)는 결과를 받고 PG 승인을 다시 부르지 않는다
        var retry = approve(reservation, key, paymentToken);
        assertEquals("APPROVED", retry.paymentStatus());
        assertEquals(1, PG.ledger().payment(orderId.toString()).orElseThrow().approveCalls());
        invariants().assertHolds();
    }

    @Test
    void c_PG_승인이_TX1보다_한참_뒤여도_같은_결과이고_승인_시각은_PG_시각이다() throws Exception {
        var reservation = newReservation();
        armApproveCrash(CrashPoint.AFTER_PG_APPROVAL);
        approveAndCrash(reservation, UUID.randomUUID());
        // TX1이 두 시간 전에 일어난 것으로 보이게 한다. PG 승인 시각(지금)은 TX1보다 한참 뒤가 된다
        jdbc.update("UPDATE payments SET created_at = now() - interval '2 hours' WHERE reservation_id = ?", reservation);

        recoverUntilIdle();

        assertEquals("APPROVED", paymentStatus(reservation));
        var gapSeconds = jdbc.queryForObject("SELECT extract(epoch FROM (approved_at - created_at)) FROM payments "
                + "WHERE reservation_id = ?", Double.class, reservation);
        assertTrue(gapSeconds > 3600, "PG 승인이 TX1보다 한참 뒤다: " + gapSeconds);
        assertEquals(1, PG.ledger().payment(pgOrderId(reservation).toString()).orElseThrow().approveCalls());
        assertEquals(List.of("PaymentApproved"), outboxEventTypes(reservation));
        invariants().assertHolds();
    }

    @Test
    void d_환불_워커가_claim한_뒤_PG_환불까지_하고_죽으면_임대가_끝난_뒤_findRefund로_확정하고_PG_환불은_한_번이다() throws Exception {
        var reservation = approvedPaymentWithRefundRequest();
        var refundId = pgRefundId(reservation);
        // 워커가 PG에 환불을 요청해 PG는 저장했지만, 결과를 반영하기 전에 죽는다
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new ProcessCrash("PG 환불 뒤 결과를 반영하기 전에 프로세스가 죽었다");
        }).when(pg).refund(any());

        assertThrows(ProcessCrash.class, refundWorker::processNext);

        assertEquals("REQUESTED", refundStatus(reservation));
        var claimed = jdbc.queryForMap("SELECT pg_requested_at, next_check_at, attempt FROM refunds WHERE pg_refund_id = ?",
                refundId);
        assertTrue(claimed.get("pg_requested_at") != null, "claim이 PG 요청 시각을 남겼다");
        assertTrue(((java.sql.Timestamp) claimed.get("next_check_at")).toInstant().isAfter(Instant.now()), "임대 중이다");
        assertEquals(1, PG.ledger().refunds(pgTxId(reservation)).refundCount(), "PG에는 환불이 남았다");
        // 임대가 끝나기 전에는 다른 워커가 이 행을 집지 못한다
        assertFalse(refundWorker.processNext());

        // 재시작한 프로세스에는 죽는 훅이 없다. 환불을 다시 부르면 호출 수로 드러나게 스텁을 푼다
        reset(pg);
        recoverUntilIdle();

        assertEquals("REFUNDED", refundStatus(reservation));
        assertEquals("REFUNDED", paymentStatus(reservation));
        var refunds = PG.ledger().refunds(pgTxId(reservation));
        assertEquals(1, refunds.refundCount());
        assertEquals(refundId.toString(), refunds.refunds().get(0).refundId());
        // pg_requested_at이 있어 findRefund가 먼저다. 환불을 다시 부르지 않으므로 호출 수가 1로 남는다
        assertEquals(1, refunds.refunds().get(0).refundCalls(), "임대 만료 뒤 환불을 다시 부르지 않았다");
        assertEquals(1, refunds.refunds().get(0).refundLookupCalls());
        assertEquals(List.of("PaymentApproved", "PaymentRefunded"), outboxEventTypes(reservation));
        invariants().assertHolds();
    }

    @Test
    void e_Outbox_릴레이가_행을_임대하고_전송하기_전에_죽으면_임대가_끝난_뒤_한_번_보내고_SENT는_한_행이다() throws Exception {
        var reservation = newReservation();
        assertEquals("APPROVED", approve(reservation, UUID.randomUUID()).paymentStatus());
        var sends = new AtomicInteger();
        var crash = new AtomicBoolean(true);
        when(sender.send(any(), any(), any())).thenAnswer(invocation -> {
            if (crash.getAndSet(false)) throw new ProcessCrash("전송 전에 릴레이 프로세스가 죽었다");
            sends.incrementAndGet();
            return CompletableFuture.completedFuture(null);
        });

        assertThrows(ProcessCrash.class, relay::relayOnce);

        assertEquals("PENDING", outboxStatus(reservation));
        var leaseUntil = jdbc.queryForObject("SELECT claimed_until FROM outbox_events WHERE message_key = ?",
                java.sql.Timestamp.class, String.valueOf(reservation));
        assertTrue(leaseUntil != null && leaseUntil.toInstant().isAfter(Instant.now()), "임대가 남았다");
        assertEquals(0, relay.relayOnce().claimed(), "임대가 끝나기 전에는 다른 릴레이가 집지 않는다");
        assertEquals(0, sends.get());

        advanceTime();
        var result = relay.relayOnce();

        assertEquals(1, result.sent());
        assertEquals(1, sends.get(), "유실 없이 한 번 나갔다");
        assertEquals("SENT", outboxStatus(reservation));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE message_key = ? AND status = 'SENT'",
                Integer.class, String.valueOf(reservation)));
        assertEquals(0, relay.relayOnce().claimed());
        invariants().assertHolds();
    }

    @Test
    void f_DLQ_처리기가_트랜잭션_중간에_죽으면_모두_롤백되고_같은_메시지를_다시_처리하면_환불과_dead_letters가_한_건이다() throws Exception {
        var reservation = newReservation();
        var approval = approve(reservation, UUID.randomUUID());
        var eventId = newEventId();
        var message = refundRequested(eventId, reservation, approval.paymentId());
        var offset = nextDlqOffset();
        // dead_letters 저장과 processed_events 기록 뒤, 환불을 만드는 단계에서 죽는다
        doAnswer(invocation -> {
            throw new ProcessCrash("DLQ 처리기가 트랜잭션 중간에 죽었다");
        }).when(refundRequests).settle(any(), any(), any(), any());

        assertThrows(ProcessCrash.class, () -> deliverToDlq(message, offset));

        assertEquals(0, refundCount(reservation), "환불이 롤백됐다");
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM dead_letters WHERE event_key = ?", Integer.class,
                eventId.toString()), "dead_letters도 롤백돼 메시지가 처리된 것으로 남지 않는다");
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM processed_events WHERE event_id = ?", Integer.class,
                eventId));

        // 재시작 뒤 오프셋이 커밋되지 않았으니 같은 메시지가 다시 온다
        org.mockito.Mockito.reset(refundRequests);
        deliverToDlq(message, offset);

        assertEquals(1, refundCount(reservation));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM dead_letters WHERE event_key = ?", Integer.class,
                eventId.toString()));
        assertEquals("AUTO_PERSISTED", jdbc.queryForObject("SELECT resolution FROM dead_letters WHERE event_key = ?",
                String.class, eventId.toString()));

        recoverUntilIdle();

        assertEquals("REFUNDED", paymentStatus(reservation));
        assertEquals(1, PG.ledger().refunds(pgTxId(reservation)).refundCount());
        invariants().assertHolds();
    }

    private String outboxStatus(long reservation) {
        return jdbc.queryForObject("SELECT status FROM outbox_events WHERE message_key = ?", String.class,
                String.valueOf(reservation));
    }
}
