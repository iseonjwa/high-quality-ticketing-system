package com.iseonjwa.payment.refund.application;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.mock;
import com.iseonjwa.payment.support.StubPgClients;
import com.iseonjwa.payment.payment.domain.PgProvider;
import com.iseonjwa.payment.support.PgClientsTestConfig;
import org.springframework.context.annotation.Import;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.iseonjwa.payment.observability.PaymentAlertLog;
import com.iseonjwa.payment.pg.application.PgClient;
import com.iseonjwa.payment.pg.application.PgIdempotencyKey;
import com.iseonjwa.payment.pg.application.PgPaymentRef;
import com.iseonjwa.payment.pg.application.PgRefundId;
import com.iseonjwa.payment.pg.application.RefundCommand;
import com.iseonjwa.payment.pg.application.RefundRejectReason;
import com.iseonjwa.payment.pg.application.UnknownCause;
import com.iseonjwa.payment.pg.infrastructure.Resilience4jPgRecoveryGate;
import com.iseonjwa.payment.refund.domain.RefundRepository;
import com.iseonjwa.payment.support.RefundWorkerFixtures;
import com.iseonjwa.payment.support.RefundWorkerFixtures.Created;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
 * 자동 환불 한 건 처리(events-and-reliability.md 6절). PG는 목이고 복구 그룹은 실제 빈이다. 환불 워커는 전역 후보를 집고 행 잠금이
 * 커밋 단위라, 트랜잭션 없이 커밋되는 행으로 확인한다.
 */
@SpringBootTest(properties = {"spring.config.import=", "spring.cloud.config.enabled=false", "eureka.client.enabled=false"})
@ActiveProfiles("test")
@Import(PgClientsTestConfig.class)
class RefundWorkerServiceTest {
    // 복구 그룹의 동시 호출 한도(payment.pg.protection.recovery.max-concurrent-calls 기본값)
    private static final int MAX_PERMITS = 2;
    private static final BigDecimal REFUND_AMOUNT = BigDecimal.valueOf(RefundWorkerFixtures.AMOUNT);
    private static final Instant PG_REFUNDED_AT = Instant.parse("2026-11-02T11:05:00.123456Z");

    @Autowired
    RefundWorkerService worker;
    @MockitoSpyBean
    RefundRepository refunds;
    @Autowired
    Resilience4jPgRecoveryGate recoveryGate;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    ObjectMapper mapper;
    @MockitoBean
    PgClient pg;

    private RefundWorkerFixtures fixtures;
    private final ListAppender<ILoggingEvent> alertLogs = new ListAppender<>();
    private final Logger alertLogger = (Logger) LoggerFactory.getLogger(PaymentAlertLog.class);

    @BeforeEach
    void setUp() {
        alertLogs.start();
        alertLogger.addAppender(alertLogs);
        reset(pg, refunds);
        recoveryGate.circuitBreaker().reset();
        fixtures = RefundWorkerFixtures.forService(jdbc);
        fixtures.cleanUp();
        fixtures.parkAllRefunds();
    }

    @AfterEach
    void tearDown() {
        alertLogger.detachAppender(alertLogs);
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
    void MOCK_결제의_환불은_토스_클라이언트가_따로_있어도_Mock으로_간다() {
        var tossPg = mock(PgClient.class);
        pgClients.route(PgProvider.TOSS, tossPg);
        var refund = fixtures.due();
        when(pg.refund(any())).thenReturn(refunded("rf-mock", REFUND_AMOUNT));

        assertTrue(worker.processNext());

        assertEquals("REFUNDED", fixtures.refundRow(refund).get("status"));
        verify(pg).refund(any());
        verifyNoInteractions(tossPg);
    }

    @Test
    void TOSS_결제의_환불은_토스_클라이언트로_간다() {
        var tossPg = mock(PgClient.class);
        pgClients.route(PgProvider.TOSS, tossPg);
        var refund = fixtures.create(RefundWorkerFixtures.spec().pgProvider("TOSS"));
        when(tossPg.refund(any())).thenReturn(refunded("rf-toss", REFUND_AMOUNT));

        assertTrue(worker.processNext());

        assertEquals("REFUNDED", fixtures.refundRow(refund).get("status"));
        verifyNoInteractions(pg);
    }

    // ── 결과 반영 (6절 환불 워커 표) ──────────────────────────────────────────────

    @Test
    void PG가_환불을_완료하면_환불과_결제를_REFUNDED로_확정하고_이력_2건과_이벤트_하나를_쓴다() throws Exception {
        var refund = fixtures.due();
        when(pg.refund(any())).thenReturn(refunded("rf-1", REFUND_AMOUNT));

        assertTrue(worker.processNext());

        var row = fixtures.refundRow(refund);
        assertEquals("REFUNDED", row.get("status"));
        assertEquals("rf-1", row.get("pg_refund_ref"));
        assertNull(row.get("next_check_at"));
        assertEquals(PG_REFUNDED_AT.toEpochMilli(), ((java.sql.Timestamp) row.get("refunded_at")).toInstant().toEpochMilli());
        assertEquals("REFUNDED", fixtures.paymentStatus(refund));
        assertEquals(1, fixtures.historyCount(refund, "REFUND"));
        assertEquals("REQUESTED->REFUNDED:", fixtures.lastHistory(refund, "REFUND"));
        assertEquals(1, fixtures.historyCount(refund, "PAYMENT"));
        assertEquals("APPROVED->REFUNDED:", fixtures.lastHistory(refund, "PAYMENT"));
        assertEquals(List.of("PaymentRefunded"), fixtures.outboxEventTypes(refund));
        var body = mapper.readTree(fixtures.outboxPayload(refund)).get("payload");
        assertEquals(refund.paymentId(), body.get("paymentId").asLong());
        assertEquals(refund.reservationId(), body.get("reservationId").asLong());
        assertEquals(42L, body.get("userId").asLong());
        assertEquals(50000, body.get("amount").asInt());
        assertEquals("KRW", body.get("currency").asString());
        assertEquals("2026-11-02T11:05:00.123Z", body.get("refundedAt").asString());
    }

    @Test
    void 환불_금액_불일치_사유의_환불이_완료되면_NEEDS_MANUAL_CHECK_결제가_REFUNDED가_되고_이벤트_금액은_환불_금액이다() throws Exception {
        var refund = fixtures.create(RefundWorkerFixtures.spec().paymentStatus("NEEDS_MANUAL_CHECK")
                .pgApprovedAmount(49000).reason("PG_AMOUNT_MISMATCH"));
        when(pg.refund(any())).thenReturn(refunded("rf-mismatch", BigDecimal.valueOf(49000)));

        assertTrue(worker.processNext());

        assertEquals("REFUNDED", fixtures.refundStatus(refund));
        assertEquals("REFUNDED", fixtures.paymentStatus(refund));
        assertEquals("NEEDS_MANUAL_CHECK->REFUNDED:", fixtures.lastHistory(refund, "PAYMENT"));
        var body = mapper.readTree(fixtures.outboxPayload(refund)).get("payload");
        assertEquals(49000, body.get("amount").asInt(), "결제 금액(50000)이 아니라 환불 금액이다");
    }

    @Test
    void PG_환불_금액이_다르면_운영자_확인으로_넘기고_결제와_이벤트는_건드리지_않는다() {
        var refund = fixtures.due();
        when(pg.refund(any())).thenReturn(refunded("rf-bad", BigDecimal.valueOf(40000)));

        assertTrue(worker.processNext());

        assertEquals("NEEDS_MANUAL_CHECK", fixtures.refundStatus(refund));
        assertEquals("REQUESTED->NEEDS_MANUAL_CHECK:REFUND_AMOUNT_MISMATCH", fixtures.lastHistory(refund, "REFUND"));
        assertEquals("APPROVED", fixtures.paymentStatus(refund));
        assertEquals(0, fixtures.outboxCount(refund));
        assertAlert(refund, "reason=REFUND_AMOUNT_MISMATCH");
    }

    @Test
    void PG_환불_통화가_다르면_운영자_확인으로_넘긴다() {
        var refund = fixtures.due();
        when(pg.refund(any())).thenReturn(new PgClient.Refunded("rf-usd", REFUND_AMOUNT, "USD", PG_REFUNDED_AT));

        assertTrue(worker.processNext());

        assertEquals("NEEDS_MANUAL_CHECK", fixtures.refundStatus(refund));
        assertEquals(0, fixtures.outboxCount(refund));
    }

    @Test
    void PG가_환불을_거절하면_FAILED가_아니라_NEEDS_MANUAL_CHECK로_넘긴다() {
        var refund = fixtures.due();
        when(pg.refund(any())).thenReturn(new PgClient.RefundRejected(RefundRejectReason.NOT_REFUNDABLE));

        assertTrue(worker.processNext());

        assertEquals("NEEDS_MANUAL_CHECK", fixtures.refundStatus(refund));
        assertEquals("REQUESTED->NEEDS_MANUAL_CHECK:REFUND_REJECTED", fixtures.lastHistory(refund, "REFUND"));
        assertEquals("APPROVED", fixtures.paymentStatus(refund));
        assertEquals(0, fixtures.outboxCount(refund));
        assertAlert(refund, "reason=REFUND_REJECTED");
    }

    @Test
    void 결과를_모르면_REQUESTED는_UNKNOWN이_되고_원인을_이력에_남기며_10초_뒤로_예약한다() {
        var refund = fixtures.due();
        when(pg.refund(any())).thenReturn(new PgClient.Unknown(UnknownCause.TIMEOUT));

        assertTrue(worker.processNext());

        assertEquals("UNKNOWN", fixtures.refundStatus(refund));
        assertEquals("REQUESTED->UNKNOWN:TIMEOUT", fixtures.lastHistory(refund, "REFUND"));
        assertAbout(Duration.ofSeconds(10), fixtures.refundNextCheckAt(refund));
        assertEquals(1, fixtures.refundAttempt(refund));
        assertEquals("APPROVED", fixtures.paymentStatus(refund));
        assertEquals(0, fixtures.outboxCount(refund));
    }

    @Test
    void UNKNOWN_환불은_다시_모르면_상태와_이력은_그대로_두고_시각만_다음_간격으로_미룬다() {
        // 이미 6번 불렀고(attempt 6) 이번이 7번째다. 7번째가 불확실하면 5분 뒤다
        var refund = fixtures.create(RefundWorkerFixtures.spec().refundStatus("UNKNOWN").attempt(6)
                .pgRequestedAt(Instant.now().minusSeconds(600)));
        when(pg.findRefund(any(), any())).thenReturn(new PgClient.Unknown(UnknownCause.CONNECTION_LOST));

        assertTrue(worker.processNext());

        assertEquals("UNKNOWN", fixtures.refundStatus(refund));
        assertEquals(7, fixtures.refundAttempt(refund));
        assertAbout(Duration.ofMinutes(5), fixtures.refundNextCheckAt(refund));
        assertEquals(0, fixtures.historyCount(refund, "REFUND"), "상태가 그대로면 이력을 쓰지 않는다");
    }

    @Test
    void 아홉_번째_호출도_모르면_소진으로_NEEDS_MANUAL_CHECK가_된다() {
        var refund = fixtures.create(RefundWorkerFixtures.spec().refundStatus("UNKNOWN").attempt(8)
                .pgRequestedAt(Instant.now().minusSeconds(600)));
        when(pg.findRefund(any(), any())).thenReturn(new PgClient.Unknown(UnknownCause.PG_ERROR));

        assertTrue(worker.processNext());

        assertEquals("NEEDS_MANUAL_CHECK", fixtures.refundStatus(refund));
        assertEquals(9, fixtures.refundAttempt(refund));
        assertEquals("UNKNOWN->NEEDS_MANUAL_CHECK:REFUND_UNRESOLVED", fixtures.lastHistory(refund, "REFUND"));
        assertEquals("APPROVED", fixtures.paymentStatus(refund));
    }

    @Test
    void 결제가_먼저_바뀌어_결제_UPDATE가_0행이면_전부_롤백하고_STATE_CONFLICT로_넘긴다() {
        var refund = fixtures.due();
        when(pg.refund(any())).thenAnswer(invocation -> {
            // PG를 부르는 사이 다른 경로가 결제를 먼저 환불 완료로 옮긴 경우를 흉내 낸다
            jdbc.update("UPDATE payments SET status = 'REFUNDED', refunded_at = now() WHERE id = ?", refund.paymentId());
            return refunded("rf-conflict", REFUND_AMOUNT);
        });

        assertTrue(worker.processNext());

        var row = fixtures.refundRow(refund);
        assertEquals("NEEDS_MANUAL_CHECK", row.get("status"), "환불만 REFUNDED로 남으면 안 된다");
        assertNull(row.get("pg_refund_ref"), "환불 전이가 롤백됐다");
        assertNull(row.get("refunded_at"));
        assertEquals(1, fixtures.historyCount(refund, "REFUND"), "롤백된 REFUNDED 이력은 남지 않고 STATE_CONFLICT 이력만 있다");
        assertEquals("REQUESTED->NEEDS_MANUAL_CHECK:STATE_CONFLICT", fixtures.lastHistory(refund, "REFUND"));
        assertEquals(0, fixtures.historyCount(refund, "PAYMENT"));
        assertEquals(0, fixtures.outboxCount(refund), "롤백됐으므로 이벤트도 없다");
    }

    @Test
    void 환불_UPDATE가_0행이면_다른_경로가_먼저_바꾼_것이라_아무것도_더_쓰지_않는다() {
        var refund = fixtures.due();
        when(pg.refund(any())).thenAnswer(invocation -> {
            // 운영자 경로가 먼저 환불을 옮겼다고 흉내 낸다
            jdbc.update("UPDATE refunds SET status = 'NEEDS_MANUAL_CHECK', next_check_at = NULL WHERE id = ?", refund.refundId());
            return refunded("rf-late", REFUND_AMOUNT);
        });

        assertTrue(worker.processNext());

        assertEquals("NEEDS_MANUAL_CHECK", fixtures.refundStatus(refund));
        assertEquals(0, fixtures.historyCount(refund, "REFUND"));
        assertEquals(0, fixtures.historyCount(refund, "PAYMENT"));
        assertEquals("APPROVED", fixtures.paymentStatus(refund));
        assertEquals(0, fixtures.outboxCount(refund));
        assertEquals(0, alertLogs.list.size(), "0행 경합이면 건별 알림 줄을 남기지 않는다");
    }

    @Test
    void PG를_부르기_전에_결제가_환불할_수_없는_상태면_부르지_않고_STATE_CONFLICT로_넘긴다() {
        var refund = fixtures.create(RefundWorkerFixtures.spec().paymentStatus("REFUNDED").pgApprovedAmount(50000));

        assertFalse(worker.processNext());

        assertEquals("NEEDS_MANUAL_CHECK", fixtures.refundStatus(refund));
        assertEquals("REQUESTED->NEEDS_MANUAL_CHECK:STATE_CONFLICT", fixtures.lastHistory(refund, "REFUND"));
        assertEquals(0, fixtures.refundAttempt(refund), "임대하지 않았으므로 시도 횟수도 세지 않는다");
        verify(pg, never()).refund(any());
        verify(pg, never()).findRefund(any(), any());
    }

    // ── 호출 선택 ─────────────────────────────────────────────────────────────

    @Test
    void 처음_부르는_환불은_findRefund_없이_refund를_멱등키와_함께_부른다() {
        var refund = fixtures.due();
        when(pg.refund(any())).thenReturn(refunded("rf-first", REFUND_AMOUNT));

        assertTrue(worker.processNext());

        var captor = org.mockito.ArgumentCaptor.forClass(RefundCommand.class);
        verify(pg, times(1)).refund(captor.capture());
        verify(pg, never()).findRefund(any(), any());
        var command = captor.getValue();
        assertEquals(new PgRefundId(refund.pgRefundId()), command.refundId());
        assertEquals(new PgPaymentRef(refund.pgTxId()), command.paymentRef());
        assertEquals(0, REFUND_AMOUNT.compareTo(command.amount()));
        assertEquals("KRW", command.currency());
        assertEquals("EXPIRED_AFTER_APPROVAL", command.reason());
        assertEquals(PgIdempotencyKey.ofRefund(new PgRefundId(refund.pgRefundId())), command.idempotencyKey());
        assertNotNull(fixtures.refundPgRequestedAt(refund));
    }

    @Test
    void 이미_요청한_적이_있으면_findRefund로_먼저_확인하고_환불됐으면_refund를_부르지_않는다() {
        var refund = fixtures.create(RefundWorkerFixtures.spec().refundStatus("UNKNOWN").attempt(1)
                .pgRequestedAt(Instant.now().minusSeconds(30)));
        when(pg.findRefund(new PgRefundId(refund.pgRefundId()), new PgPaymentRef(refund.pgTxId())))
                .thenReturn(refunded("rf-found", REFUND_AMOUNT));

        assertTrue(worker.processNext());

        assertEquals("REFUNDED", fixtures.refundStatus(refund));
        assertEquals("REFUNDED", fixtures.paymentStatus(refund));
        assertEquals("UNKNOWN->REFUNDED:", fixtures.lastHistory(refund, "REFUND"));
        verify(pg, never()).refund(any());
        assertEquals(1, fixtures.outboxCount(refund));
    }

    @Test
    void 첫_호출이_불확실했으면_다음_처리는_refund가_아니라_findRefund로_시작한다() {
        var refund = fixtures.due();
        when(pg.refund(any())).thenReturn(new PgClient.Unknown(UnknownCause.CONNECTION_LOST));
        assertTrue(worker.processNext());
        assertEquals("UNKNOWN", fixtures.refundStatus(refund));
        // 10초를 기다리지 않고 차례가 된 것으로 만든다
        jdbc.update("UPDATE refunds SET next_check_at = now() - interval '1 second' WHERE id = ?", refund.refundId());
        when(pg.findRefund(any(), any())).thenReturn(refunded("rf-second", REFUND_AMOUNT));

        assertTrue(worker.processNext());

        verify(pg, times(1)).refund(any());
        verify(pg, times(1)).findRefund(new PgRefundId(refund.pgRefundId()), new PgPaymentRef(refund.pgTxId()));
        assertEquals("REFUNDED", fixtures.refundStatus(refund));
        assertEquals(2, fixtures.refundAttempt(refund));
        assertEquals(1, fixtures.outboxCount(refund));
    }

    @Test
    void 조회가_NotFound면_같은_pg_refund_id로_refund를_다시_부른다() {
        var refund = fixtures.create(RefundWorkerFixtures.spec().refundStatus("UNKNOWN").attempt(1)
                .pgRequestedAt(Instant.now().minusSeconds(30)));
        when(pg.findRefund(any(), any())).thenReturn(new PgClient.NotFound());
        when(pg.refund(any())).thenReturn(refunded("rf-again", REFUND_AMOUNT));

        assertTrue(worker.processNext());

        var captor = org.mockito.ArgumentCaptor.forClass(RefundCommand.class);
        verify(pg, times(1)).findRefund(any(), any());
        verify(pg, times(1)).refund(captor.capture());
        assertEquals(new PgRefundId(refund.pgRefundId()), captor.getValue().refundId(), "같은 환불 ID로 다시 부른다");
        assertEquals(PgIdempotencyKey.ofRefund(new PgRefundId(refund.pgRefundId())), captor.getValue().idempotencyKey());
        assertEquals("REFUNDED", fixtures.refundStatus(refund));
        assertEquals(1, fixtures.outboxCount(refund));
    }

    @Test
    void 조회가_NotFound인데_두_번째_허가를_못_얻으면_부르지_않고_다음_간격으로_재예약한다() {
        var refund = fixtures.create(RefundWorkerFixtures.spec().attempt(1).pgRequestedAt(Instant.now().minusSeconds(30)));
        when(pg.findRefund(any(), any())).thenAnswer(invocation -> {
            // 조회 직후 복구 그룹이 열린 상황을 만든다. 두 번째 허가를 얻지 못한다
            recoveryGate.circuitBreaker().transitionToOpenState();
            return new PgClient.NotFound();
        });

        assertTrue(worker.processNext());

        verify(pg, never()).refund(any());
        assertEquals("UNKNOWN", fixtures.refundStatus(refund));
        assertEquals("REQUESTED->UNKNOWN:RECOVERY_PERMIT_DENIED", fixtures.lastHistory(refund, "REFUND"));
        assertEquals(2, fixtures.refundAttempt(refund), "허가를 못 얻어도 이미 센 시도는 유지한다");
        assertAbout(Duration.ofSeconds(10), fixtures.refundNextCheckAt(refund));
    }

    @Test
    void PG_결과가_null이면_불확실로_다룬다() {
        var refund = fixtures.due();
        when(pg.refund(any())).thenReturn(null);

        assertTrue(worker.processNext());

        assertEquals("UNKNOWN", fixtures.refundStatus(refund));
        assertEquals("REQUESTED->UNKNOWN:PG_ERROR", fixtures.lastHistory(refund, "REFUND"));
    }

    @Test
    void PG_조회_결과가_null이면_불확실로_다룬다() {
        var refund = fixtures.create(RefundWorkerFixtures.spec().refundStatus("UNKNOWN").attempt(1)
                .pgRequestedAt(Instant.now().minusSeconds(30)));
        when(pg.findRefund(any(), any())).thenReturn(null);

        assertTrue(worker.processNext());

        assertEquals("UNKNOWN", fixtures.refundStatus(refund));
        assertEquals(2, fixtures.refundAttempt(refund));
    }

    @Test
    void PG_호출이_예외로_끝나도_불확실로_다루고_허가를_돌려준다() {
        var refund = fixtures.due();
        when(pg.refund(any())).thenThrow(new IllegalStateException("어댑터 버그"));

        assertTrue(worker.processNext());

        assertEquals("UNKNOWN", fixtures.refundStatus(refund));
        assertEquals("REQUESTED->UNKNOWN:PG_ERROR", fixtures.lastHistory(refund, "REFUND"));
    }

    @Test
    void CLIENT_ERROR는_불확실로_다루되_서킷브레이커_실패로_세지_않는다() {
        var refund = fixtures.due();
        when(pg.refund(any())).thenReturn(new PgClient.Unknown(UnknownCause.CLIENT_ERROR));

        assertTrue(worker.processNext());

        assertEquals("UNKNOWN", fixtures.refundStatus(refund));
        assertEquals(0, recoveryGate.circuitBreaker().getMetrics().getNumberOfFailedCalls());
    }

    // ── 허가·임대·동시성 ──────────────────────────────────────────────────────

    @Test
    void 복구_그룹이_열려_있으면_claim하지_않고_시도_횟수도_세지_않는다() {
        var refund = fixtures.due();
        var before = fixtures.refundNextCheckAt(refund);
        recoveryGate.circuitBreaker().transitionToOpenState();

        assertFalse(worker.processNext());

        assertEquals(0, fixtures.refundAttempt(refund));
        assertNull(fixtures.refundPgRequestedAt(refund), "임대하지 않았으므로 첫 호출 표시도 하지 않는다");
        assertEquals(before.toEpochMilli(), fixtures.refundNextCheckAt(refund).toEpochMilli());
        verify(pg, never()).refund(any());
        verify(pg, never()).findRefund(any(), any());
    }

    @Test
    void 처리할_차례가_아닌_환불은_집지_않는다() {
        fixtures.create(RefundWorkerFixtures.spec().nextCheckAt(Instant.now().plusSeconds(60)));

        assertFalse(worker.processNext());

        verify(pg, never()).refund(any());
    }

    @Test
    void 두_스레드가_같은_환불을_동시에_처리하려_해도_PG는_한_번만_부른다() throws Exception {
        var refund = fixtures.due();
        when(pg.refund(any())).thenReturn(refunded("rf-once", REFUND_AMOUNT));
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> {
                start.await();
                return worker.processNext();
            });
            var second = executor.submit(() -> {
                start.await();
                return worker.processNext();
            });
            start.countDown();

            var handled = (first.get(10, TimeUnit.SECONDS) ? 1 : 0) + (second.get(10, TimeUnit.SECONDS) ? 1 : 0);

            assertEquals(1, handled);
        }
        verify(pg, times(1)).refund(any());
        assertEquals(1, fixtures.outboxCount(refund));
    }

    @Test
    void PG_호출은_트랜잭션_밖에서_한다() {
        var refund = fixtures.due();
        when(pg.refund(any())).thenAnswer(invocation -> {
            // 트랜잭션 안이면 PG가 느릴 때 DB 연결을 붙잡는다(7절). 실패하면 AssertionError가 워커를 거쳐 테스트로 전파된다
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive(), "PG 환불이 트랜잭션 안에서 불렸다");
            return refunded("rf-tx", REFUND_AMOUNT);
        });
        when(pg.findRefund(any(), any())).thenAnswer(invocation -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive(), "PG 환불 조회가 트랜잭션 안에서 불렸다");
            return new PgClient.NotFound();
        });

        assertTrue(worker.processNext());
        assertEquals("REFUNDED", fixtures.refundStatus(refund));

        var again = fixtures.create(RefundWorkerFixtures.spec().attempt(1).pgRequestedAt(Instant.now().minusSeconds(30)));
        assertTrue(worker.processNext());
        assertEquals("REFUNDED", fixtures.refundStatus(again));
    }

    @Test
    void claim_트랜잭션_안에서_Error가_나도_허가를_돌려준다() {
        fixtures.due();
        doThrow(new AssertionError("Error 시뮬레이션")).when(refunds).claim(any(), any(), any(), any());

        assertThrows(AssertionError.class, () -> worker.processNext());

        // 허가 반환은 tearDown이 확인한다
        assertEquals(MAX_PERMITS, recoveryGate.bulkhead().getMetrics().getAvailableConcurrentCalls());
    }

    // 예약한 시각이 지금부터 기대한 간격 안팎(±3초)에 있는지 본다
    // 건별 알림 줄이 정확히 한 건이고, 환불·결제 번호와 서울 시각이 붙는다
    private void assertAlert(Created refund, String reason) {
        assertEquals(1, alertLogs.list.size());
        var event = alertLogs.list.get(0);
        assertEquals(Level.ERROR, event.getLevel());
        var line = event.getFormattedMessage();
        assertTrue(line.startsWith("event=REFUND_NEEDS_MANUAL_CHECK paymentId=" + refund.paymentId() + " userId=42 "), line);
        assertTrue(line.matches(".* paidAt=\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\+09:00 refundId="
                + refund.refundId() + " " + reason), line);
    }

    private static void assertAbout(Duration expected, Instant actual) {
        var delta = Duration.between(Instant.now(), actual).minus(expected).abs();
        assertTrue(delta.compareTo(Duration.ofSeconds(3)) < 0, "예약 시각이 기대와 다르다 actual=" + actual);
    }

    private static PgClient.Refunded refunded(String refundRef, BigDecimal amount) {
        return new PgClient.Refunded(refundRef, amount, "KRW", PG_REFUNDED_AT);
    }
}
