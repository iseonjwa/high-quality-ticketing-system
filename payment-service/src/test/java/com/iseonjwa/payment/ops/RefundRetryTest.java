package com.iseonjwa.payment.ops;

import com.iseonjwa.payment.ops.application.OpsArguments;
import com.iseonjwa.payment.ops.application.OpsPgLookup;
import com.iseonjwa.payment.ops.application.OpsPlanHash;
import com.iseonjwa.payment.ops.application.OpsProperties;
import com.iseonjwa.payment.ops.application.OpsRunner;
import com.iseonjwa.payment.ops.application.RefundRetryCommand;
import com.iseonjwa.payment.ops.domain.ManualActionLogRepository;
import com.iseonjwa.payment.ops.domain.OpsInspectionRepository;
import com.iseonjwa.payment.pg.application.PgClient;
import com.iseonjwa.payment.pg.application.PgClient.NotFound;
import com.iseonjwa.payment.pg.application.PgClient.RefundRejected;
import com.iseonjwa.payment.pg.application.PgClient.Refunded;
import com.iseonjwa.payment.pg.application.PgClient.Unknown;
import com.iseonjwa.payment.pg.application.RefundCommand;
import com.iseonjwa.payment.pg.application.RefundRejectReason;
import com.iseonjwa.payment.pg.application.UnknownCause;
import com.iseonjwa.payment.refund.application.RefundRetryService;
import com.iseonjwa.payment.refund.application.RefundWorkerService;
import com.iseonjwa.payment.support.AppendOnlyCleanup;
import com.iseonjwa.payment.support.RefundWorkerFixtures;
import com.iseonjwa.payment.support.RefundWorkerFixtures.Created;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * refund retry의 dry-run·적용·거부를 실제 PostgreSQL과 목 PgClient로 확인한다(N30). 적용과 거부가 커밋되어야 하므로
 * 테스트 트랜잭션으로 감싸지 않고 끝나면 이 범위의 행을 지운다. 환불 워커는 적용 뒤 직접 불러 이어받는지 본다.
 */
// Note: 이 설정의 컨텍스트는 다른 테스트가 재사용하지 못한다. 캐시에 남으면 커넥션 풀이 테스트 DB의 max_connections(100)를 채운다
@SpringBootTest(properties = {"spring.config.import=", "spring.cloud.config.enabled=false", "eureka.client.enabled=false"})
@ActiveProfiles("test")
@DirtiesContext
class RefundRetryTest {
    private static final Instant PG_REFUNDED_AT = Instant.parse("2026-11-03T02:00:00Z");
    private static final BigDecimal AMOUNT = BigDecimal.valueOf(RefundWorkerFixtures.AMOUNT);

    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired ManualActionLogRepository audit;
    @Autowired OpsInspectionRepository inspection;
    @Autowired RefundRetryService retryService;
    @Autowired RefundWorkerService worker;
    @Autowired OpsProperties properties;
    @Autowired ObjectProvider<PgClient> pgProvider;
    @Autowired @Qualifier("commonAuditingClock") Clock clock;
    @MockitoBean PgClient pg;

    RefundWorkerFixtures fixtures;
    RefundRetryCommand command;
    OpsRunner runner;
    ByteArrayOutputStream captured;
    PrintStream out;

    @BeforeEach
    void setUp() {
        reset(pg);
        fixtures = RefundWorkerFixtures.forService(jdbc);
        cleanUp();
        fixtures.parkAllRefunds();
        command = new RefundRetryCommand(inspection, new OpsPgLookup(pgProvider), retryService);
        runner = new OpsRunner(List.of(command), List.of(), properties, transactionManager, audit, clock);
        captured = new ByteArrayOutputStream();
        out = new PrintStream(captured, true, StandardCharsets.UTF_8);
    }

    @AfterEach
    void cleanUp() {
        fixtures.cleanUp();
        AppendOnlyCleanup.deleteManualActionLogs(jdbc, "command = ?", RefundRetryCommand.NAME);
    }

    @Test
    void N30_PG에_환불_기록이_없으면_REQUESTED로_되돌리고_멱등키와_요청_시각은_그대로_둔다() {
        var refund = nmcRefund();
        var before = jdbc.queryForMap("SELECT pg_refund_id, pg_requested_at FROM refunds WHERE id = ?", refund.refundId());
        when(pg.findRefund(any(), any())).thenReturn(new NotFound());
        var planHash = hash(refund.refundId());

        var exit = run("--operator=alice", "--approver=bob", "--refund-id=" + refund.refundId(), "--apply",
                "--confirm=" + planHash, "--reason=PG 장애가 풀렸다");

        assertEquals(OpsRunner.EXIT_OK, exit, output());
        assertRequeued(refund, before);
        assertEquals(List.of("NEEDS_MANUAL_CHECK->REQUESTED:OPS_RETRY"), refundHistories(refund));
        assertEquals("OPS:alice", jdbc.queryForObject(
                "SELECT actor FROM payment_status_histories WHERE payment_id = ? AND target_type = 'REFUND'", String.class,
                refund.paymentId()));
        var log = onlyLog();
        assertEquals("APPLIED", log.get("result"));
        assertEquals("REFUND", log.get("target_type"));
        assertEquals("NEEDS_MANUAL_CHECK", log.get("from_status"));
        assertEquals("REQUESTED", log.get("to_status"));
        assertEquals("bob", log.get("approver"));
        assertEquals(planHash, log.get("plan_hash"));
        assertTrue(log.get("pg_evidence").toString().contains("\"lookup\": \"NotFound\""), log.toString());
        verify(pg, never()).refund(any());
    }

    @Test
    void PG가_이전_환불을_거절했어도_원인을_고쳤다면_REQUESTED로_되돌린다() {
        var refund = nmcRefund();
        var before = jdbc.queryForMap("SELECT pg_refund_id, pg_requested_at FROM refunds WHERE id = ?", refund.refundId());
        when(pg.findRefund(any(), any())).thenReturn(new RefundRejected(RefundRejectReason.NOT_REFUNDABLE));

        var exit = run("--operator=alice", "--approver=bob", "--refund-id=" + refund.refundId(), "--apply",
                "--confirm=" + hash(refund.refundId()), "--reason=PG 설정을 고쳤다");

        assertEquals(OpsRunner.EXIT_OK, exit, output());
        assertRequeued(refund, before);
        assertTrue(onlyLog().get("pg_evidence").toString().contains("\"lookup\": \"RefundRejected\""));
        verify(pg, never()).refund(any());
    }

    @Test
    void 적용_뒤_환불_워커는_findRefund로_먼저_확인하고_같은_pg_refund_id로_한_번만_환불한다() {
        var refund = nmcRefund();
        when(pg.findRefund(any(), any())).thenReturn(new NotFound());
        var exit = run("--operator=alice", "--approver=bob", "--refund-id=" + refund.refundId(), "--apply",
                "--confirm=" + hash(refund.refundId()), "--reason=PG 장애가 풀렸다");
        assertEquals(OpsRunner.EXIT_OK, exit, output());
        clearInvocations(pg);
        fixtures.parkAllRefundsExcept(refund.refundId());
        when(pg.refund(any())).thenReturn(new Refunded("rf-retry", AMOUNT, "KRW", PG_REFUNDED_AT));

        assertTrue(worker.processNext());

        // pg_requested_at이 남아 있어서 첫 호출이 아니다. findRefund가 먼저고 refund는 그다음 한 번이다
        InOrder order = inOrder(pg);
        order.verify(pg).findRefund(any(), any());
        var captor = ArgumentCaptor.forClass(RefundCommand.class);
        order.verify(pg).refund(captor.capture());
        verify(pg, times(1)).refund(any());
        assertEquals(refund.pgRefundId().toString(), captor.getValue().refundId().value().toString());
        assertEquals("REFUNDED", fixtures.refundStatus(refund));
        assertEquals("REFUNDED", fixtures.paymentStatus(refund));
        assertEquals(List.of("PaymentRefunded"), fixtures.outboxEventTypes(refund));
    }

    @Test
    void PG가_이미_환불했다고_하면_거부하고_refund_resolve를_안내한다() {
        var refund = nmcRefund();
        when(pg.findRefund(any(), any())).thenReturn(new Refunded("rf-done", AMOUNT, "KRW", PG_REFUNDED_AT));

        assertEquals(OpsRunner.EXIT_REJECTED, run("--operator=alice", "--refund-id=" + refund.refundId()));
        assertTrue(output().contains("refund resolve"), output());

        var exit = run("--operator=alice", "--approver=bob", "--refund-id=" + refund.refundId(), "--apply",
                "--confirm=" + "0".repeat(64), "--reason=시도");
        assertEquals(OpsRunner.EXIT_REJECTED, exit, output());
        assertUntouched(refund);
        assertEquals("REJECTED", onlyLog().get("result"));
    }

    @Test
    void PG_결과를_모르면_거부한다() {
        var refund = nmcRefund();
        when(pg.findRefund(any(), any())).thenReturn(new Unknown(UnknownCause.PG_ERROR));
        assertEquals(OpsRunner.EXIT_REJECTED, run("--operator=alice", "--refund-id=" + refund.refundId()));
        assertTrue(output().contains("PG 결과를 알 수 없어"), output());

        reset(pg);
        when(pg.findRefund(any(), any())).thenThrow(new IllegalStateException("연결 실패"));
        assertEquals(OpsRunner.EXIT_REJECTED, run("--operator=alice", "--refund-id=" + refund.refundId()));
        assertUntouched(refund);
    }

    @Test
    void 결제가_환불_사유에_맞는_상태가_아니면_PG를_부르지_않고_거부한다() {
        var refund = fixtures.create(RefundWorkerFixtures.spec().refundStatus("NEEDS_MANUAL_CHECK")
                .paymentStatus("REFUNDED").pgRequestedAt(Instant.now().minusSeconds(600)));
        clearNextCheck(refund);

        var exit = run("--operator=alice", "--refund-id=" + refund.refundId());

        assertEquals(OpsRunner.EXIT_REJECTED, exit);
        assertTrue(output().contains("STATE_CONFLICT"), output());
        assertEquals("NEEDS_MANUAL_CHECK", fixtures.refundStatus(refund));
        verify(pg, never()).findRefund(any(), any());
    }

    @Test
    void 환불_사유가_기대하는_결제_상태와_다르면_거부한다() {
        // 일반 사유의 환불은 APPROVED 결제를 기대한다. 금액 불일치로 NEEDS_MANUAL_CHECK가 된 결제에 붙은 것은 어긋난다
        var refund = fixtures.create(RefundWorkerFixtures.spec().refundStatus("NEEDS_MANUAL_CHECK")
                .paymentStatus("NEEDS_MANUAL_CHECK").pgApprovedAmount(49000));
        clearNextCheck(refund);

        assertEquals(OpsRunner.EXIT_REJECTED, run("--operator=alice", "--refund-id=" + refund.refundId()));
        assertTrue(output().contains("APPROVED 상태가 아니라"), output());
    }

    @Test
    void 금액_불일치_환불은_결제가_NEEDS_MANUAL_CHECK일_때_되돌릴_수_있다() {
        var refund = fixtures.create(RefundWorkerFixtures.spec().refundStatus("NEEDS_MANUAL_CHECK")
                .reason("PG_AMOUNT_MISMATCH").pgApprovedAmount(49000).paymentStatus("NEEDS_MANUAL_CHECK")
                .pgRequestedAt(Instant.now().minusSeconds(60)));
        clearNextCheck(refund);
        when(pg.findRefund(any(), any())).thenReturn(new NotFound());

        var exit = run("--operator=alice", "--approver=bob", "--refund-id=" + refund.refundId(), "--apply",
                "--confirm=" + hash(refund.refundId()), "--reason=PG 설정을 고쳤다");

        assertEquals(OpsRunner.EXIT_OK, exit, output());
        assertEquals("REQUESTED", fixtures.refundStatus(refund));
    }

    @Test
    void NEEDS_MANUAL_CHECK가_아닌_환불은_거부한다() {
        var refund = fixtures.create(RefundWorkerFixtures.spec().refundStatus("REQUESTED"));

        var exit = run("--operator=alice", "--approver=bob", "--refund-id=" + refund.refundId(), "--apply",
                "--confirm=" + "0".repeat(64), "--reason=시도");

        assertEquals(OpsRunner.EXIT_REJECTED, exit, output());
        assertTrue(output().contains("현재 상태는 REQUESTED"), output());
        assertEquals("REQUESTED", fixtures.refundStatus(refund));
        assertEquals("REJECTED", onlyLog().get("result"));
        verify(pg, never()).findRefund(any(), any());
    }

    @Test
    void 승인자가_없으면_거부하고_환불을_바꾸지_않는다() {
        var refund = nmcRefund();
        when(pg.findRefund(any(), any())).thenReturn(new NotFound());

        var exit = run("--operator=alice", "--refund-id=" + refund.refundId(), "--apply", "--confirm=" + hash(refund.refundId()),
                "--reason=혼자 시도");

        assertEquals(OpsRunner.EXIT_REJECTED, exit, output());
        assertTrue(output().contains("4-eyes"), output());
        assertUntouched(refund);
        assertEquals("REJECTED", onlyLog().get("result"));
    }

    @Test
    void dry_run은_상태를_바꾸지_않고_감사_기록도_남기지_않는다() {
        var refund = nmcRefund();
        when(pg.findRefund(any(), any())).thenReturn(new NotFound());

        assertEquals(OpsRunner.EXIT_OK, run("--operator=alice", "--refund-id=" + refund.refundId()));

        assertUntouched(refund);
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM manual_action_logs WHERE command = ?", Integer.class,
                RefundRetryCommand.NAME));
        assertTrue(output().contains("계획 해시: "), output());
    }

    private Created nmcRefund() {
        var refund = fixtures.create(RefundWorkerFixtures.spec().refundStatus("NEEDS_MANUAL_CHECK").attempt(9)
                .pgRequestedAt(Instant.now().minusSeconds(3600)));
        clearNextCheck(refund);
        return refund;
    }

    // 워커가 운영자 확인으로 넘길 때와 같이 next_check_at을 비운다
    private void clearNextCheck(Created refund) {
        jdbc.update("UPDATE refunds SET next_check_at = NULL WHERE id = ?", refund.refundId());
    }

    private void assertRequeued(Created refund, java.util.Map<String, Object> before) {
        var row = fixtures.refundRow(refund);
        assertEquals("REQUESTED", row.get("status"));
        assertEquals(0, ((Number) row.get("attempt")).intValue());
        assertNotNull(row.get("next_check_at"));
        assertTrue(!((Timestamp) row.get("next_check_at")).toInstant().isAfter(Instant.now().plusSeconds(1)));
        assertEquals(before.get("pg_refund_id"), row.get("pg_refund_id"));
        assertNotNull(row.get("pg_requested_at"), "pg_requested_at을 비우면 워커가 findRefund 없이 refund부터 부른다");
        assertEquals(before.get("pg_requested_at"), row.get("pg_requested_at"));
    }

    private void assertUntouched(Created refund) {
        var row = fixtures.refundRow(refund);
        assertEquals("NEEDS_MANUAL_CHECK", row.get("status"));
        assertEquals(9, ((Number) row.get("attempt")).intValue());
        assertEquals(0, fixtures.historyCount(refund, "REFUND"));
    }

    private List<String> refundHistories(Created refund) {
        return jdbc.queryForList("""
                SELECT from_status || '->' || to_status || ':' || coalesce(reason, '') FROM payment_status_histories
                WHERE payment_id = ? AND target_type = 'REFUND' ORDER BY id""", String.class, refund.paymentId());
    }

    private int run(String... options) {
        return runner.run(arguments(options), out);
    }

    private OpsArguments arguments(String... options) {
        var parsed = new LinkedHashMap<String, List<String>>();
        for (var option : options) {
            var body = option.substring(2);
            var separator = body.indexOf('=');
            if (separator < 0) {
                parsed.put(body, List.of());
            } else {
                parsed.put(body.substring(0, separator), List.of(body.substring(separator + 1)));
            }
        }
        return new OpsArguments(List.of("ops", "refund", "retry"), parsed);
    }

    private String hash(long refundId) {
        return OpsPlanHash.of(RefundRetryCommand.NAME, command.plan(arguments("--refund-id=" + refundId)));
    }

    private java.util.Map<String, Object> onlyLog() {
        var logs = jdbc.queryForList("SELECT * FROM manual_action_logs WHERE command = ?", RefundRetryCommand.NAME);
        assertEquals(1, logs.size(), logs.toString());
        return logs.get(0);
    }

    private String output() {
        return captured.toString(StandardCharsets.UTF_8);
    }
}
