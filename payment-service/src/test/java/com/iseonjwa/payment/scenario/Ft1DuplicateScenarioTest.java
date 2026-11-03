package com.iseonjwa.payment.scenario;

import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.iseonjwa.payment.support.PaymentTokenFixtures.token;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * FT-1 메시지 중복(failure-scenarios.md 4절). 결제 쪽에서 중복 승인·중복 환불·중복 전송이 일어나지 않음을 실제 Mock PG와
 * 테스트 PostgreSQL로 확인하고, 시나리오마다 끝에서 V1~V3를 모두 실행한다.
 *
 * <ul>
 *   <li>(a) 승인 재요청: 같은 키·새 키(ALIAS)·같은 예매의 동시 요청.</li>
 *   <li>(b) {@code RefundRequested} 3중 발행과 DLQ 재전달.</li>
 *   <li>(c) Outbox 전송 확인 전 중단 뒤 같은 {@code eventId}로 다시 나간다.</li>
 *   <li>(d) {@code processed_events} 정리 뒤 재전달은 {@code RedeliveryAfterRetentionCleanupTest}(ISE-146)가 이미 다룬다.</li>
 * </ul>
 *
 * <p>Note: (a)의 {@code PaymentApproved} 중복 수신을 거르는 것은 소비자(예매)의 {@code processed_events}다. 결제 쪽은 이벤트가 같은
 * {@code eventId}로 다시 나가는 데까지만 확인한다.
 */
@Order(ScenarioTestBase.RUN_FIRST)
class Ft1DuplicateScenarioTest extends ScenarioTestBase {
    private static final int CONCURRENT_REQUESTS = 6;

    @Test
    void a_같은_키_3회와_새_키_2회는_기존_결제로_응답하고_결제는_한_건_PG_승인은_한_번이다() throws Exception {
        var reservation = newReservation();
        var paymentToken = token(USER, reservation, AMOUNT).build();
        var key = UUID.randomUUID();

        var first = approve(reservation, key, paymentToken);
        var sameKeyAgain = approve(reservation, key, paymentToken);
        var sameKeyThird = approve(reservation, key, paymentToken);
        var newKey = UUID.randomUUID();
        var aliasFirst = approve(reservation, newKey, paymentToken);
        var aliasSecond = approve(reservation, UUID.randomUUID(), paymentToken);

        assertEquals(200, first.status(), String.valueOf(first.body()));
        assertEquals("APPROVED", first.paymentStatus());
        for (var response : List.of(sameKeyAgain, sameKeyThird, aliasFirst, aliasSecond)) {
            assertEquals(200, response.status(), String.valueOf(response.body()));
            assertEquals(first.paymentId(), response.paymentId());
        }
        assertEquals(1, paymentCount(reservation));
        assertEquals(1, PG.ledger().payment(pgOrderId(reservation).toString()).orElseThrow().approveCalls());

        recoverUntilIdle();

        assertEquals(List.of("PaymentApproved"), outboxEventTypes(reservation));
        invariants().assertHolds();
    }

    @Test
    void a_같은_예매로_서로_다른_키와_같은_키가_동시에_와도_결제는_한_건이고_PG_승인은_한_번이다() throws Exception {
        var reservation = newReservation();
        var paymentToken = token(USER, reservation, AMOUNT).build();
        var sharedKey = UUID.randomUUID();
        var barrier = new CyclicBarrier(CONCURRENT_REQUESTS);
        var tasks = new ArrayList<Callable<Approval>>();
        for (var i = 0; i < CONCURRENT_REQUESTS; i++) {
            // 절반은 같은 키(N14), 나머지는 서로 다른 새 키(N13)로 보낸다
            var key = i % 2 == 0 ? sharedKey : UUID.randomUUID();
            tasks.add(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                return approve(reservation, key, paymentToken);
            });
        }
        var pool = Executors.newFixedThreadPool(CONCURRENT_REQUESTS);
        List<Approval> responses = new ArrayList<>();
        try {
            for (var future : pool.invokeAll(tasks)) responses.add(future.get());
        } finally {
            pool.shutdownNow();
        }

        // 진 쪽도 500이 아니라 기존 결제로 응답한다. PG 호출 중이면 202, 끝났으면 200이다
        var paymentIds = responses.stream().map(Approval::paymentId).distinct().toList();
        for (var response : responses) {
            assertTrue(response.status() == 200 || response.status() == 202, String.valueOf(response.body()));
        }
        assertEquals(1, paymentIds.size(), "응답의 paymentId가 모두 같아야 한다: " + paymentIds);
        assertEquals(1, paymentCount(reservation));
        assertEquals(1, PG.ledger().payment(pgOrderId(reservation).toString()).orElseThrow().approveCalls());

        recoverUntilIdle();

        assertEquals("APPROVED", paymentStatus(reservation));
        assertEquals(List.of("PaymentApproved"), outboxEventTypes(reservation));
        invariants().assertHolds();
    }

    @Test
    void b_같은_RefundRequested_3번과_같은_DLQ_메시지_재전달에도_환불은_한_건이고_PG_환불은_한_번이다() throws Exception {
        var reservation = newReservation();
        var approval = approve(reservation, UUID.randomUUID());
        assertEquals("APPROVED", approval.paymentStatus());
        var paymentId = approval.paymentId();
        var eventId = newEventId();
        var message = refundRequested(eventId, reservation, paymentId);

        for (var i = 0; i < 3; i++) commandListener.onMessage(message);
        assertEquals(1, refundCount(reservation), "3중 발행에도 환불 행은 한 건이다");
        // 같은 원본 메시지가 DLQ로 두 번 들어온다(원본 오프셋 커밋 실패 뒤 재처리). 같은 eventId라 한 건으로 묶인다
        var offset = nextDlqOffset();
        deliverToDlq(message, offset);
        deliverToDlq(message, offset);
        assertEquals(1, refundCount(reservation), "DLQ 재전달에도 환불 행은 한 건이다");
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM dead_letters WHERE event_key = ?", Integer.class,
                eventId.toString()));
        assertEquals("DUPLICATE", jdbc.queryForObject("SELECT resolution FROM dead_letters WHERE event_key = ?",
                String.class, eventId.toString()), "이미 환불이 있으니 DLQ 처리기는 중복으로 닫는다");

        recoverUntilIdle();

        assertEquals("REFUNDED", refundStatus(reservation));
        assertEquals("REFUNDED", paymentStatus(reservation));
        var refunds = PG.ledger().refunds(pgTxId(reservation));
        assertEquals(1, refunds.refundCount());
        assertEquals(1, refunds.refunds().get(0).refundCalls(), "PG 환불 호출도 한 번이다");
        assertEquals(List.of("PaymentApproved", "PaymentRefunded"), outboxEventTypes(reservation));
        invariants().assertHolds();
    }

    @Test
    void b_DLQ로_먼저_도착해_환불이_만들어진_뒤_원본이_다시_전달돼도_환불은_한_건이다() throws Exception {
        var reservation = newReservation();
        var approval = approve(reservation, UUID.randomUUID());
        var paymentId = approval.paymentId();
        var eventId = newEventId();
        var message = refundRequested(eventId, reservation, paymentId);

        deliverToDlq(message, nextDlqOffset());
        assertEquals("AUTO_PERSISTED", jdbc.queryForObject("SELECT resolution FROM dead_letters WHERE event_key = ?",
                String.class, eventId.toString()));
        // DLQ 처리기는 소비 그룹이 달라 원본 소비자의 처리 이력을 남기지 않는다. 원본이 다시 전달돼도 환불은 늘지 않는다
        commandListener.onMessage(message);
        commandListener.onMessage(message);
        assertEquals(1, refundCount(reservation));

        recoverUntilIdle();

        assertEquals("REFUNDED", paymentStatus(reservation));
        var refunds = PG.ledger().refunds(pgTxId(reservation));
        assertEquals(1, refunds.refundCount());
        assertEquals(1, refunds.refunds().get(0).refundCalls());
        invariants().assertHolds();
    }

    @Test
    void c_Outbox가_전송_확인_전에_중단되면_임대가_끝난_뒤_같은_eventId로_다시_나가고_SENT는_한_행이다() throws Exception {
        var reservation = newReservation();
        assertEquals("APPROVED", approve(reservation, UUID.randomUUID()).paymentStatus());
        var outboxEventId = jdbc.queryForObject("SELECT event_id FROM outbox_events WHERE message_key = ?", UUID.class,
                String.valueOf(reservation));
        var sentEventIds = new CopyOnWriteArrayList<String>();
        var crashBeforeConfirm = new AtomicBoolean(true);
        var mapper = JsonMapper.builder().build();
        // 첫 전송은 브로커에 도달했지만 확인을 받기 전에 릴레이가 죽은 것을 흉내 낸다. 보낸 기록은 남고 SENT 기록은 없다
        when(sender.send(any(), any(), any())).thenAnswer(invocation -> {
            sentEventIds.add(mapper.readTree((String) invocation.getArgument(2)).get("eventId").asString());
            if (crashBeforeConfirm.getAndSet(false)) throw new BrokerAckLost();
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        });

        assertThrows(BrokerAckLost.class, relay::relayOnce);
        assertEquals("PENDING", outboxStatus(reservation), "확인을 못 받았으니 SENT가 아니다");
        assertEquals(0, relay.relayOnce().claimed(), "임대가 끝나기 전에는 다시 집히지 않는다");
        assertEquals(1, sentEventIds.size());

        advanceTime();
        var second = relay.relayOnce();

        assertEquals(1, second.sent());
        assertEquals(List.of(outboxEventId.toString(), outboxEventId.toString()), sentEventIds,
                "같은 eventId로 두 번 나간다. 중복 수신은 소비자의 processed_events가 거른다");
        assertEquals("SENT", outboxStatus(reservation));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE message_key = ? AND status = 'SENT'",
                Integer.class, String.valueOf(reservation)));
        assertEquals(0, relay.relayOnce().claimed(), "SENT가 된 뒤에는 더 보내지 않는다");
        assertEquals(2, sentEventIds.size());

        invariants().assertHolds();
    }

    private String outboxStatus(long reservation) {
        return jdbc.queryForObject("SELECT status FROM outbox_events WHERE message_key = ?", String.class,
                String.valueOf(reservation));
    }

    /** 릴레이가 브로커 확인을 기다리다 죽는 일을 흉내 내는 오류. 릴레이는 예외로 잡지 않고 임대를 풀지 않은 채 끝난다. */
    private static final class BrokerAckLost extends Error {
        BrokerAckLost() {
            super("브로커 확인 전에 릴레이가 중단됐다", null, false, false);
        }
    }
}
