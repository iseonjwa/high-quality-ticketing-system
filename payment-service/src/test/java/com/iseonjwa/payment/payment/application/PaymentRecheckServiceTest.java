package com.iseonjwa.payment.payment.application;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.mock;
import com.iseonjwa.payment.support.StubPgClients;
import com.iseonjwa.payment.payment.domain.PgProvider;
import com.iseonjwa.payment.support.PgClientsTestConfig;
import org.springframework.context.annotation.Import;
import com.iseonjwa.payment.payment.domain.DeclineReason;
import com.iseonjwa.payment.payment.domain.Payment;
import com.iseonjwa.payment.payment.domain.PaymentRepository;
import com.iseonjwa.payment.payment.domain.PaymentStatus;
import com.iseonjwa.payment.payment.domain.StatusActor;
import com.iseonjwa.payment.pg.application.PgClient;
import com.iseonjwa.payment.pg.application.PgOrderId;
import com.iseonjwa.payment.pg.application.PgPaymentRef;
import com.iseonjwa.payment.pg.application.UnknownCause;
import com.iseonjwa.payment.pg.infrastructure.Resilience4jPgRecoveryGate;
import com.iseonjwa.payment.support.RecheckFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.iseonjwa.payment.support.RecheckFixtures.AMOUNT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 결제 재확인 한 건 처리(events-and-reliability.md 6절, E9·E12). PG는 목이고 복구 그룹은 실제 빈이다.
 * 재확인은 전역 후보를 집고 행 잠금이 커밋 단위라, 트랜잭션 없이 커밋되는 행으로 확인한다.
 */
@SpringBootTest(properties = {"spring.config.import=", "spring.cloud.config.enabled=false", "eureka.client.enabled=false"})
@ActiveProfiles("test")
@Import(PgClientsTestConfig.class)
class PaymentRecheckServiceTest {
    // 복구 그룹의 동시 호출 한도(payment.pg.protection.recovery.max-concurrent-calls 기본값)
    private static final int MAX_PERMITS = 2;

    @Autowired
    PaymentRecheckService recheck;
    @Autowired
    PaymentTransitionService transitions;
    @MockitoSpyBean
    PaymentRepository payments;
    @Autowired
    Resilience4jPgRecoveryGate recoveryGate;
    @Autowired
    JdbcTemplate jdbc;
    @MockitoBean
    PgClient pg;

    private RecheckFixtures fixtures;

    @BeforeEach
    void setUp() {
        reset(pg, payments);
        recoveryGate.circuitBreaker().reset();
        fixtures = new RecheckFixtures(payments, jdbc);
        fixtures.cleanUp();
        fixtures.parkAllRecheckCandidates();
    }

    @AfterEach
    void tearDown() {
        // 어떤 경로로 끝나도 허가가 새지 않아야 한다(0018)
        assertEquals(MAX_PERMITS, recoveryGate.bulkhead().getMetrics().getAvailableConcurrentCalls(), "복구 허가가 새고 있다");
        recoveryGate.circuitBreaker().reset();
        fixtures.cleanUp();
    }

    @Autowired
    StubPgClients pgClients;

    @AfterEach
    void resetRoutes() {
        pgClients.reset();
    }

    @Test
    void MOCK_결제는_토스_클라이언트가_따로_있어도_Mock으로_조회해_확정한다() {
        var tossPg = mock(PgClient.class);
        pgClients.route(PgProvider.TOSS, tossPg);
        var payment = fixtures.due(PaymentStatus.UNKNOWN, 1, PgProvider.MOCK);
        when(pg.findPayment(orderOf(payment))).thenReturn(approved(AMOUNT));

        assertTrue(recheck.recheckNext());

        assertEquals(PaymentStatus.APPROVED, fixtures.reload(payment).getStatus());
        verifyNoInteractions(tossPg);
    }

    @Test
    void TOSS_결제는_토스_클라이언트로_조회한다() {
        var tossPg = mock(PgClient.class);
        pgClients.route(PgProvider.TOSS, tossPg);
        var payment = fixtures.due(PaymentStatus.UNKNOWN, 1, PgProvider.TOSS);
        when(tossPg.findPayment(orderOf(payment))).thenReturn(approved(AMOUNT));

        assertTrue(recheck.recheckNext());

        assertEquals(PaymentStatus.APPROVED, fixtures.reload(payment).getStatus());
        verifyNoInteractions(pg);
    }

    @Test
    void 토스_키_없는_서버에서_TOSS_결제를_만나도_예외로_멈추지_않고_결과_모름으로_다룬다() {
        pgClients.missing(PgProvider.TOSS);
        var payment = fixtures.due(PaymentStatus.REQUESTED, 0, PgProvider.TOSS);

        assertTrue(recheck.recheckNext());

        assertEquals(PaymentStatus.UNKNOWN, fixtures.reload(payment).getStatus());
        verifyNoInteractions(pg);
    }

    @Test
    void REQUESTED가_PG에서_승인으로_확인되면_승인으로_확정하고_이벤트를_하나_쓴다() {
        var payment = fixtures.due(PaymentStatus.REQUESTED, 0);
        when(pg.findPayment(orderOf(payment))).thenReturn(approved(AMOUNT));

        assertTrue(recheck.recheckNext());

        var confirmed = fixtures.reload(payment);
        assertEquals(PaymentStatus.APPROVED, confirmed.getStatus());
        assertEquals("pg-tx-recheck", confirmed.getPgTxId());
        assertNull(confirmed.getNextCheckAt());
        assertEquals(1, fixtures.historyCount(payment));
        assertEquals(1, fixtures.outboxCount(payment));
        assertEquals("PaymentApproved", fixtures.outboxEventType(payment));
    }

    @Test
    void UNKNOWN은_재확인으로_승인이_확인된_뒤에만_이벤트가_나간다() {
        var payment = fixtures.due(PaymentStatus.UNKNOWN, 2);
        when(pg.findPayment(orderOf(payment))).thenReturn(approved(AMOUNT));
        assertEquals(0, fixtures.outboxCount(payment), "확정 전에는 이벤트가 없다");

        assertTrue(recheck.recheckNext());

        assertEquals(PaymentStatus.APPROVED, fixtures.reload(payment).getStatus());
        assertEquals(1, fixtures.outboxCount(payment));
        assertEquals("PaymentApproved", fixtures.outboxEventType(payment));
    }

    @Test
    void PG가_거절했으면_거절로_확정하고_이벤트를_쓴다() {
        var payment = fixtures.due(PaymentStatus.UNKNOWN, 1);
        when(pg.findPayment(orderOf(payment))).thenReturn(new PgClient.Declined(DeclineReason.CARD_DECLINED));

        assertTrue(recheck.recheckNext());

        var confirmed = fixtures.reload(payment);
        assertEquals(PaymentStatus.DECLINED, confirmed.getStatus());
        assertEquals(DeclineReason.CARD_DECLINED, confirmed.getDeclineCode());
        assertEquals("PaymentDeclined", fixtures.outboxEventType(payment));
    }

    @Test
    void PG가_모르는_주문이면_늦은_승인을_막고_거절로_확정한다() {
        var payment = fixtures.due(PaymentStatus.REQUESTED, 0);
        when(pg.findPayment(orderOf(payment))).thenReturn(new PgClient.NotFound());
        when(pg.preventLateApproval(orderOf(payment))).thenReturn(new PgClient.Prevented());

        assertTrue(recheck.recheckNext());

        var confirmed = fixtures.reload(payment);
        assertEquals(PaymentStatus.DECLINED, confirmed.getStatus());
        assertEquals(DeclineReason.NOT_SENT, confirmed.getDeclineCode());
        assertEquals("PaymentDeclined", fixtures.outboxEventType(payment));
    }

    @Test
    void 늦은_승인_방지가_이미_승인됐다고_답하면_승인으로_확정한다() {
        var payment = fixtures.due(PaymentStatus.UNKNOWN, 3);
        when(pg.findPayment(orderOf(payment))).thenReturn(new PgClient.NotFound());
        when(pg.preventLateApproval(orderOf(payment))).thenReturn(new PgClient.AlreadyApproved(
                new PgPaymentRef("pg-tx-late"), AMOUNT, "KRW", Instant.now()));

        assertTrue(recheck.recheckNext());

        var confirmed = fixtures.reload(payment);
        assertEquals(PaymentStatus.APPROVED, confirmed.getStatus());
        assertEquals("pg-tx-late", confirmed.getPgTxId());
        assertEquals("PaymentApproved", fixtures.outboxEventType(payment));
    }

    @Test
    void 늦은_승인_방지도_결과를_모르면_UNKNOWN으로_다시_예약한다() {
        var payment = fixtures.due(PaymentStatus.REQUESTED, 0);
        when(pg.findPayment(orderOf(payment))).thenReturn(new PgClient.NotFound());
        when(pg.preventLateApproval(orderOf(payment))).thenReturn(new PgClient.Unknown(UnknownCause.TIMEOUT));

        assertTrue(recheck.recheckNext());

        var rescheduled = fixtures.reload(payment);
        assertEquals(PaymentStatus.UNKNOWN, rescheduled.getStatus());
        assertEquals(1, rescheduled.getUnknownRetryCount());
        assertAbout(Duration.ofSeconds(10), rescheduled.getNextCheckAt());
        assertEquals(0, fixtures.outboxCount(payment));
    }

    @Test
    void REQUESTED가_불확실하면_UNKNOWN으로_바꾸고_10초_뒤로_예약한다() {
        var payment = fixtures.due(PaymentStatus.REQUESTED, 0);
        when(pg.findPayment(orderOf(payment))).thenReturn(new PgClient.Unknown(UnknownCause.TIMEOUT));

        assertTrue(recheck.recheckNext());

        var rescheduled = fixtures.reload(payment);
        assertEquals(PaymentStatus.UNKNOWN, rescheduled.getStatus());
        assertEquals(1, rescheduled.getUnknownRetryCount());
        assertAbout(Duration.ofSeconds(10), rescheduled.getNextCheckAt());
        assertEquals(1, fixtures.historyCount(payment));
    }

    @Test
    void UNKNOWN이_여섯_번째에도_불확실하면_상태는_두고_1분_뒤로_예약한다() {
        var payment = fixtures.due(PaymentStatus.UNKNOWN, 5);
        when(pg.findPayment(orderOf(payment))).thenReturn(new PgClient.Unknown(UnknownCause.CONNECTION_LOST));

        assertTrue(recheck.recheckNext());

        var rescheduled = fixtures.reload(payment);
        assertEquals(PaymentStatus.UNKNOWN, rescheduled.getStatus());
        assertEquals(6, rescheduled.getUnknownRetryCount());
        assertAbout(Duration.ofMinutes(1), rescheduled.getNextCheckAt());
        assertEquals(0, fixtures.historyCount(payment), "상태가 같아 이력이 없다");
    }

    @Test
    void 아홉_번째에도_불확실하면_운영자_확인으로_넘긴다() {
        var payment = fixtures.due(PaymentStatus.UNKNOWN, 8);
        when(pg.findPayment(orderOf(payment))).thenReturn(new PgClient.Unknown(UnknownCause.TIMEOUT));

        assertTrue(recheck.recheckNext());

        var exhausted = fixtures.reload(payment);
        assertEquals(PaymentStatus.NEEDS_MANUAL_CHECK, exhausted.getStatus());
        assertEquals(9, exhausted.getUnknownRetryCount());
        assertEquals("PG_UNRESOLVED", fixtures.lastHistoryReason(payment));
        assertEquals(0, fixtures.outboxCount(payment));
    }

    @Test
    void PG를_조회하다_예외가_나면_불확실로_다룬다() {
        var payment = fixtures.due(PaymentStatus.REQUESTED, 0);
        when(pg.findPayment(orderOf(payment))).thenThrow(new IllegalStateException("어댑터 오류"));

        assertTrue(recheck.recheckNext());

        assertEquals(PaymentStatus.UNKNOWN, fixtures.reload(payment).getStatus());
    }

    @Test
    void 승인_금액이_다르면_운영자_확인으로_넘기고_이벤트를_내지_않는다() {
        var payment = fixtures.due(PaymentStatus.UNKNOWN, 1);
        when(pg.findPayment(orderOf(payment))).thenReturn(approved(BigDecimal.valueOf(49000)));

        assertTrue(recheck.recheckNext());

        var mismatch = fixtures.reload(payment);
        assertEquals(PaymentStatus.NEEDS_MANUAL_CHECK, mismatch.getStatus());
        assertEquals("PG_AMOUNT_MISMATCH", fixtures.lastHistoryReason(payment));
        assertEquals("pg-tx-recheck", mismatch.getPgTxId());
        assertEquals(0, fixtures.outboxCount(payment));
    }

    @Test
    void 복구_그룹의_CB가_열려_있으면_claim하지_않고_시도_횟수도_세지_않는다() {
        var payment = fixtures.due(PaymentStatus.UNKNOWN, 2);
        var before = fixtures.reload(payment);
        recoveryGate.circuitBreaker().transitionToOpenState();

        assertFalse(recheck.recheckNext());

        var after = fixtures.reload(payment);
        assertEquals(2, after.getUnknownRetryCount());
        assertEquals(before.getNextCheckAt().toEpochMilli(), after.getNextCheckAt().toEpochMilli());
        verify(pg, never()).findPayment(any());
    }

    @Test
    void 확인할_차례가_오지_않은_결제는_집지_않는다() {
        fixtures.create(PaymentStatus.UNKNOWN, 1, Instant.now().plusSeconds(60));

        assertFalse(recheck.recheckNext());

        verify(pg, never()).findPayment(any());
    }

    @Test
    void 두_스레드가_같은_결제를_동시에_처리하려_해도_PG는_한_번만_부른다() throws Exception {
        var payment = fixtures.due(PaymentStatus.REQUESTED, 0);
        when(pg.findPayment(orderOf(payment))).thenReturn(approved(AMOUNT));
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> {
                start.await();
                return recheck.recheckNext();
            });
            var second = executor.submit(() -> {
                start.await();
                return recheck.recheckNext();
            });
            start.countDown();

            var handled = (first.get(10, TimeUnit.SECONDS) ? 1 : 0) + (second.get(10, TimeUnit.SECONDS) ? 1 : 0);

            assertEquals(1, handled);
        }
        verify(pg, times(1)).findPayment(orderOf(payment));
        assertEquals(1, fixtures.outboxCount(payment));
    }

    @Test
    void 조회하는_사이_다른_경로가_먼저_확정하면_재확인은_아무것도_더_쓰지_않는다() {
        var payment = fixtures.due(PaymentStatus.REQUESTED, 0);
        when(pg.findPayment(orderOf(payment))).thenAnswer(invocation -> {
            // 승인 API의 TX2가 먼저 끝난 경우를 흉내 낸다
            transitions.approve(payment.getId(), PaymentStatus.REQUESTED, "pg-tx-other", AMOUNT, Instant.now(),
                    StatusActor.SYSTEM);
            return approved(AMOUNT);
        });

        assertTrue(recheck.recheckNext());

        var confirmed = fixtures.reload(payment);
        assertEquals(PaymentStatus.APPROVED, confirmed.getStatus());
        assertEquals("pg-tx-other", confirmed.getPgTxId());
        assertEquals(1, fixtures.historyCount(payment), "재확인의 0행 전이는 이력을 쓰지 않는다");
        assertEquals(1, fixtures.outboxCount(payment), "이벤트가 중복되지 않는다");
    }

    @Test
    void PG_조회_결과가_null이면_불확실로_다룬다() {
        var payment = fixtures.due(PaymentStatus.REQUESTED, 0);
        when(pg.findPayment(orderOf(payment))).thenReturn(null);

        assertTrue(recheck.recheckNext());

        assertEquals(PaymentStatus.UNKNOWN, fixtures.reload(payment).getStatus());
    }

    @Test
    void 늦은_승인_방지_결과가_null이면_불확실로_다룬다() {
        var payment = fixtures.due(PaymentStatus.REQUESTED, 0);
        when(pg.findPayment(orderOf(payment))).thenReturn(new PgClient.NotFound());
        when(pg.preventLateApproval(orderOf(payment))).thenReturn(null);

        assertTrue(recheck.recheckNext());

        assertEquals(PaymentStatus.UNKNOWN, fixtures.reload(payment).getStatus());
    }

    @Test
    void 늦은_승인_방지_허가를_못_얻으면_호출하지_않고_다음_간격으로_재예약한다() {
        var payment = fixtures.due(PaymentStatus.REQUESTED, 0);
        when(pg.findPayment(orderOf(payment))).thenAnswer(invocation -> {
            // 조회 직후 복구 그룹이 열린 상황을 만든다. 두 번째 허가를 얻지 못한다
            recoveryGate.circuitBreaker().transitionToOpenState();
            return new PgClient.NotFound();
        });

        assertTrue(recheck.recheckNext());

        var rescheduled = fixtures.reload(payment);
        assertEquals(PaymentStatus.UNKNOWN, rescheduled.getStatus());
        assertEquals(1, rescheduled.getUnknownRetryCount(), "허가를 못 얻어도 이미 센 시도는 유지한다");
        assertAbout(Duration.ofSeconds(10), rescheduled.getNextCheckAt());
        assertEquals("RECOVERY_PERMIT_DENIED", fixtures.lastHistoryReason(payment));
        verify(pg, never()).preventLateApproval(any());
    }

    @Test
    void PG_조회는_트랜잭션_밖에서_한다() {
        var payment = fixtures.due(PaymentStatus.REQUESTED, 0);
        when(pg.findPayment(orderOf(payment))).thenAnswer(invocation -> {
            // 트랜잭션 안이면 PG가 느릴 때 DB 연결을 붙잡는다(7절). 실패하면 AssertionError가 재확인을 거쳐 테스트로 전파된다
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive(), "PG 조회가 트랜잭션 안에서 불렸다");
            return approved(AMOUNT);
        });

        assertTrue(recheck.recheckNext());

        assertEquals(PaymentStatus.APPROVED, fixtures.reload(payment).getStatus());
    }

    @Test
    void claim_트랜잭션_안에서_Error가_나도_허가를_돌려준다() {
        fixtures.due(PaymentStatus.REQUESTED, 0);
        var maxPermits = recoveryGate.bulkhead().getMetrics().getAvailableConcurrentCalls();
        doThrow(new AssertionError("Error 시뮬레이션")).when(payments).claimRecheck(any(), any(), any(), any());

        assertThrows(AssertionError.class, () -> recheck.recheckNext());

        assertEquals(maxPermits, recoveryGate.bulkhead().getMetrics().getAvailableConcurrentCalls());
    }

    private static PgOrderId orderOf(Payment payment) {
        return new PgOrderId(payment.getPgOrderId());
    }

    private static PgClient.Approved approved(BigDecimal amount) {
        return new PgClient.Approved(new PgPaymentRef("pg-tx-recheck"), amount, "KRW", Instant.now());
    }

    // 예약한 시각이 지금부터 기대한 간격 안팎(±3초)에 있는지 본다
    private static void assertAbout(Duration expected, Instant actual) {
        var delta = Duration.between(Instant.now(), actual).minus(expected).abs();
        assertTrue(delta.compareTo(Duration.ofSeconds(3)) < 0, "예약 시각이 기대와 다르다 actual=" + actual);
    }
}
