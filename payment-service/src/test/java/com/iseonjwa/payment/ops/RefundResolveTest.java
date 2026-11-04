package com.iseonjwa.payment.ops;

import com.iseonjwa.payment.ops.application.OpsArguments;
import com.iseonjwa.payment.ops.application.OpsPgLookup;
import com.iseonjwa.payment.ops.application.OpsPlanHash;
import com.iseonjwa.payment.ops.application.OpsProperties;
import com.iseonjwa.payment.ops.application.OpsRunner;
import com.iseonjwa.payment.ops.application.RefundResolveCommand;
import com.iseonjwa.payment.ops.domain.ManualActionLogRepository;
import com.iseonjwa.payment.ops.domain.OpsInspectionRepository;
import com.iseonjwa.payment.pg.application.PgClient.NotFound;
import com.iseonjwa.payment.pg.application.PgClient.RefundRejected;
import com.iseonjwa.payment.pg.application.PgClient.Refunded;
import com.iseonjwa.payment.pg.application.PgClient.Unknown;
import com.iseonjwa.payment.pg.application.RefundRejectReason;
import com.iseonjwa.payment.pg.application.UnknownCause;
import com.iseonjwa.payment.refund.application.RefundSettlementService;
import com.iseonjwa.payment.support.AppendOnlyCleanup;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * refund resolve의 dry-run·적용·거부를 실제 PostgreSQL과 가짜 PgClient로 확인한다(N31). 적용과 거부가 커밋되어야 하므로
 * 테스트 트랜잭션으로 감싸지 않고 이 범위의 예매 ID로 만든 행을 끝나면 지운다.
 */
@SpringBootTest(properties = {"spring.config.import=", "spring.cloud.config.enabled=false", "eureka.client.enabled=false"})
@ActiveProfiles("test")
class RefundResolveTest {
    private static final Instant PG_REFUNDED_AT = Instant.parse("2026-11-03T02:00:00Z");

    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired ManualActionLogRepository audit;
    @Autowired OpsInspectionRepository inspection;
    @Autowired RefundSettlementService settlement;
    @Autowired OpsProperties properties;
    @Autowired @Qualifier("commonAuditingClock") Clock clock;

    final ScriptedPgClient pg = new ScriptedPgClient();
    OpsFixtures fixtures;
    RefundResolveCommand command;
    OpsRunner runner;
    ByteArrayOutputStream captured;
    PrintStream out;
    long reservation;

    @BeforeEach
    void setUp() {
        cleanUp();
        fixtures = new OpsFixtures(jdbc);
        command = new RefundResolveCommand(inspection, new OpsPgLookup(pg.asProvider()), settlement);
        runner = new OpsRunner(List.of(command), List.of(), properties, transactionManager, audit, clock);
        captured = new ByteArrayOutputStream();
        out = new PrintStream(captured, true, StandardCharsets.UTF_8);
        reservation = OpsFixtures.nextReservation();
    }

    @AfterEach
    void cleanUp() {
        var range = "reservation_id BETWEEN " + OpsFixtures.FIRST_RESERVATION + " AND " + OpsFixtures.LAST_RESERVATION;
        AppendOnlyCleanup.deleteHistories(jdbc, "payment_id IN (SELECT id FROM payments WHERE " + range + ")");
        AppendOnlyCleanup.deleteManualActionLogs(jdbc, "command = ?", RefundResolveCommand.NAME);
        jdbc.update("DELETE FROM refunds WHERE payment_id IN (SELECT id FROM payments WHERE " + range + ")");
        jdbc.update("DELETE FROM payments WHERE " + range);
        jdbc.update("DELETE FROM outbox_events WHERE message_key ~ '^91[0-9]{5}$'");
    }

    @Test
    void N31_PG가_환불_완료를_확인했고_금액이_같으면_환불과_결제를_REFUNDED로_옮기고_PaymentRefunded를_남긴다() {
        var refundId = nmcRefund();
        pg.refund = refunded("console-cancel-1", 50000);
        var planHash = hash(refundId, "--external-ref=TOSS-CONSOLE-9");

        var exit = run("--operator=alice", "--approver=bob", "--refund-id=" + refundId, "--external-ref=TOSS-CONSOLE-9",
                "--apply", "--confirm=" + planHash, "--reason=콘솔 환불 뒤 PG 조회로 확인");

        assertEquals(OpsRunner.EXIT_OK, exit, output());
        var refund = jdbc.queryForMap("SELECT status, pg_refund_ref, refunded_at, next_check_at FROM refunds WHERE id = ?", refundId);
        assertEquals("REFUNDED", refund.get("status"));
        assertEquals("console-cancel-1", refund.get("pg_refund_ref"));
        assertEquals(PG_REFUNDED_AT, ((java.sql.Timestamp) refund.get("refunded_at")).toInstant());
        assertNull(refund.get("next_check_at"));
        var payment = jdbc.queryForMap("SELECT status, refunded_at FROM payments WHERE reservation_id = ?", reservation);
        assertEquals("REFUNDED", payment.get("status"));
        assertEquals(PG_REFUNDED_AT, ((java.sql.Timestamp) payment.get("refunded_at")).toInstant());
        assertEquals(1, outboxCount("PaymentRefunded"));
        assertEquals(0, pg.refundCalls, "Ops 명령은 PG 환불을 부르지 않는다");

        var histories = jdbc.queryForList("SELECT target_type, from_status, to_status, actor FROM payment_status_histories"
                + " WHERE payment_id = (SELECT id FROM payments WHERE reservation_id = ?) ORDER BY id", reservation);
        assertEquals(List.of("REFUND NEEDS_MANUAL_CHECK->REFUNDED OPS:alice", "PAYMENT APPROVED->REFUNDED OPS:alice"),
                histories.stream().map(row -> row.get("target_type") + " " + row.get("from_status") + "->"
                        + row.get("to_status") + " " + row.get("actor")).toList());

        var log = onlyLog();
        assertEquals("APPLIED", log.get("result"));
        assertEquals("REFUND", log.get("target_type"));
        assertEquals("NEEDS_MANUAL_CHECK", log.get("from_status"));
        assertEquals("REFUNDED", log.get("to_status"));
        assertEquals("TOSS-CONSOLE-9", log.get("external_ref"));
        assertEquals("bob", log.get("approver"));
        assertEquals(planHash, log.get("plan_hash"));
        assertTrue(log.get("pg_evidence").toString().contains("\"lookup\": \"Refunded\""), log.toString());
    }

    @Test
    void 금액_불일치로_시작한_환불은_운영자_확인_상태의_결제를_REFUNDED로_옮기고_이벤트_금액은_PG_승인_금액이다() {
        var paymentId = fixtures.needsManualCheckPayment(reservation, "pg-tx-m", 49000);
        var refundId = fixtures.refund(paymentId, "NEEDS_MANUAL_CHECK", "PG_AMOUNT_MISMATCH", 49000);
        pg.refund = refunded("pg-refund-m", 49000);

        var exit = run("--operator=alice", "--approver=bob", "--refund-id=" + refundId, "--apply",
                "--confirm=" + hash(refundId), "--reason=PG에서 환불 완료를 확인");

        assertEquals(OpsRunner.EXIT_OK, exit, output());
        assertEquals("REFUNDED", jdbc.queryForObject("SELECT status FROM payments WHERE id = ?", String.class, paymentId));
        assertEquals(0, new BigDecimal("49000").compareTo(jdbc.queryForObject(
                "SELECT (payload->'payload'->>'amount')::numeric FROM outbox_events WHERE message_key = ? AND event_type = 'PaymentRefunded'",
                BigDecimal.class, String.valueOf(reservation))));
    }

    @Test
    void PG의_환불_금액이_다르면_거부하고_아무것도_바꾸지_않는다() {
        var refundId = nmcRefund();
        pg.refund = refunded("console-cancel-2", 30000);

        assertEquals(OpsRunner.EXIT_REJECTED, run("--operator=alice", "--refund-id=" + refundId));
        assertEquals(OpsRunner.EXIT_REJECTED, run("--operator=alice", "--approver=bob", "--refund-id=" + refundId,
                "--apply", "--confirm=" + "a".repeat(64), "--reason=시도"));

        assertTrue(output().contains("실제 환불 금액이 다를 때의 종결 절차는 아직 없습니다"), output());
        assertEquals("NEEDS_MANUAL_CHECK", refundStatus(refundId));
        assertEquals("APPROVED", paymentStatus());
        assertEquals(0, outboxCount("PaymentRefunded"));
        assertEquals("REJECTED", onlyLog().get("result"));
    }

    @Test
    void PG가_거절했고_close_failed와_external_ref가_함께_있으면_FAILED로_닫고_결제는_그대로_둔다() {
        var refundId = nmcRefund();
        pg.refund = new RefundRejected(RefundRejectReason.values()[0]);
        var planHash = hash(refundId, "--close-failed", "--external-ref=BANK-TRANSFER-77");

        var exit = run("--operator=alice", "--approver=bob", "--refund-id=" + refundId, "--close-failed",
                "--external-ref=BANK-TRANSFER-77", "--apply", "--confirm=" + planHash, "--reason=고객 계좌로 직접 환불함");

        assertEquals(OpsRunner.EXIT_OK, exit, output());
        assertEquals("FAILED", refundStatus(refundId));
        assertEquals("APPROVED", paymentStatus());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE message_key = ?", Integer.class,
                String.valueOf(reservation)), "종결은 이벤트를 내지 않는다");
        var history = jdbc.queryForMap("SELECT target_type, from_status, to_status, actor FROM payment_status_histories"
                + " WHERE payment_id = (SELECT id FROM payments WHERE reservation_id = ?)", reservation);
        assertEquals("REFUND", history.get("target_type"));
        assertEquals("FAILED", history.get("to_status"));
        assertEquals("OPS:alice", history.get("actor"));
        var log = onlyLog();
        assertEquals("APPLIED", log.get("result"));
        assertEquals("FAILED", log.get("to_status"));
        assertEquals("BANK-TRANSFER-77", log.get("external_ref"));
        assertEquals(0, pg.refundCalls, "Ops 명령은 PG 환불을 부르지 않는다");
    }

    @Test
    void PG가_거절했어도_close_failed나_external_ref가_하나라도_없으면_안내와_함께_거부한다() {
        var refundId = nmcRefund();
        pg.refund = new RefundRejected(RefundRejectReason.values()[0]);

        assertEquals(OpsRunner.EXIT_REJECTED, run("--operator=alice", "--refund-id=" + refundId));
        assertTrue(output().contains("--close-failed와 --external-ref"), output());
        assertEquals(OpsRunner.EXIT_REJECTED, run("--operator=alice", "--refund-id=" + refundId, "--close-failed"));
        assertEquals(OpsRunner.EXIT_REJECTED, run("--operator=alice", "--refund-id=" + refundId, "--external-ref=BANK-1"));
        assertEquals(OpsRunner.EXIT_REJECTED, run("--operator=alice", "--approver=bob", "--refund-id=" + refundId,
                "--close-failed", "--apply", "--confirm=" + "b".repeat(64), "--reason=시도"));

        assertEquals("NEEDS_MANUAL_CHECK", refundStatus(refundId));
        assertEquals("REJECTED", onlyLog().get("result"));
    }

    @Test
    void PG에_환불_기록이_없으면_콘솔_환불처럼_확인할_수_없는_경우도_거부하고_NMC로_남긴다() {
        var refundId = nmcRefund();
        pg.refund = new NotFound();

        assertEquals(OpsRunner.EXIT_REJECTED, run("--operator=alice", "--approver=bob", "--refund-id=" + refundId,
                "--external-ref=TOSS-CONSOLE-1", "--apply", "--confirm=" + "c".repeat(64), "--reason=콘솔 환불 뒤 확인"));

        assertTrue(output().contains("PG에 환불 기록이 없습니다") && output().contains("refund retry"), output());
        assertEquals("NEEDS_MANUAL_CHECK", refundStatus(refundId));
        assertEquals("APPROVED", paymentStatus());
        assertEquals(0, outboxCount("PaymentRefunded"));
        assertEquals("REJECTED", onlyLog().get("result"));
    }

    @Test
    void close_failed를_환불_완료나_미발견에_쓰면_거부한다() {
        var refundId = nmcRefund();
        pg.refund = refunded("r-1", 50000);
        assertEquals(OpsRunner.EXIT_REJECTED, run("--operator=alice", "--refund-id=" + refundId, "--close-failed",
                "--external-ref=BANK-1"));
        assertTrue(output().contains("--close-failed는 PG가 환불을 거절한 경우에만"), output());

        pg.refund = new NotFound();
        assertEquals(OpsRunner.EXIT_REJECTED, run("--operator=alice", "--refund-id=" + refundId, "--close-failed",
                "--external-ref=BANK-1"));
        assertEquals("NEEDS_MANUAL_CHECK", refundStatus(refundId));
    }

    @Test
    void PG_결과를_모르거나_조회가_예외면_거부한다() {
        var refundId = nmcRefund();
        pg.refund = new Unknown(UnknownCause.TIMEOUT);
        assertEquals(OpsRunner.EXIT_REJECTED, run("--operator=alice", "--refund-id=" + refundId));

        pg.failure = new IllegalStateException("연결 실패");
        assertEquals(OpsRunner.EXIT_REJECTED, run("--operator=alice", "--refund-id=" + refundId));
        assertEquals("NEEDS_MANUAL_CHECK", refundStatus(refundId));
    }

    @Test
    void NEEDS_MANUAL_CHECK가_아닌_환불은_거부하고_PG를_조회하지_않는다() {
        var paymentId = fixtures.approvedPayment(reservation);
        var refundId = fixtures.refund(paymentId, "REQUESTED");
        pg.refund = refunded("r-2", 50000);

        assertEquals(OpsRunner.EXIT_REJECTED, run("--operator=alice", "--refund-id=" + refundId));

        assertTrue(output().contains("NEEDS_MANUAL_CHECK인 환불만"), output());
        assertEquals(0, pg.findRefundCalls);
    }

    @Test
    void dry_run_뒤_PG_결과가_바뀌면_해시가_달라져_거부한다() {
        var refundId = nmcRefund();
        pg.refund = refunded("r-3", 50000);
        var stale = hash(refundId);
        pg.refund = new RefundRejected(RefundRejectReason.values()[0]);

        var exit = run("--operator=alice", "--approver=bob", "--refund-id=" + refundId, "--apply", "--confirm=" + stale,
                "--reason=시도");

        assertEquals(OpsRunner.EXIT_REJECTED, exit);
        assertEquals("NEEDS_MANUAL_CHECK", refundStatus(refundId));
        assertEquals("REJECTED", onlyLog().get("result"));
    }

    @Test
    void 같은_명령을_두_번_적용하면_두_번째는_거부하고_이벤트는_한_건이다() {
        var refundId = nmcRefund();
        pg.refund = refunded("r-4", 50000);
        var arguments = new String[] {"--operator=alice", "--approver=bob", "--refund-id=" + refundId, "--apply",
                "--confirm=" + hash(refundId), "--reason=적용"};

        assertEquals(OpsRunner.EXIT_OK, run(arguments));
        assertEquals(OpsRunner.EXIT_REJECTED, run(arguments));

        assertEquals(1, outboxCount("PaymentRefunded"));
    }

    @Test
    void 결제가_환불을_반영할_수_없는_상태면_적용_전에_거부한다() {
        var paymentId = fixtures.needsManualCheckPayment(reservation, "pg-tx-s", 50000);
        var refundId = fixtures.refund(paymentId, "NEEDS_MANUAL_CHECK");
        pg.refund = refunded("r-5", 50000);

        assertEquals(OpsRunner.EXIT_REJECTED, run("--operator=alice", "--refund-id=" + refundId));

        assertTrue(output().contains("결제가 APPROVED 상태가 아니라"), output());
    }

    @Test
    void 승인자가_없으면_PG를_조회하기_전에_거부한다() {
        var refundId = nmcRefund();
        pg.refund = refunded("r-6", 50000);

        assertEquals(OpsRunner.EXIT_REJECTED, run("--operator=alice", "--refund-id=" + refundId, "--apply",
                "--confirm=" + "d".repeat(64), "--reason=시도"));

        assertEquals(0, pg.findRefundCalls);
    }

    private long nmcRefund() {
        var paymentId = fixtures.approvedPayment(reservation);
        return fixtures.refund(paymentId, "NEEDS_MANUAL_CHECK");
    }

    private Refunded refunded(String refundRef, int amount) {
        return new Refunded(refundRef, new BigDecimal(amount), "KRW", PG_REFUNDED_AT);
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
        return new OpsArguments(List.of("ops", "refund", "resolve"), parsed);
    }

    private String hash(long refundId, String... extra) {
        var options = new String[extra.length + 1];
        options[0] = "--refund-id=" + refundId;
        System.arraycopy(extra, 0, options, 1, extra.length);
        return OpsPlanHash.of(RefundResolveCommand.NAME, command.plan(arguments(options)));
    }

    private String refundStatus(long refundId) {
        return jdbc.queryForObject("SELECT status FROM refunds WHERE id = ?", String.class, refundId);
    }

    private String paymentStatus() {
        return jdbc.queryForObject("SELECT status FROM payments WHERE reservation_id = ?", String.class, reservation);
    }

    private int outboxCount(String eventType) {
        return jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE message_key = ? AND event_type = ?",
                Integer.class, String.valueOf(reservation), eventType);
    }

    private Map<String, Object> onlyLog() {
        var logs = jdbc.queryForList("SELECT * FROM manual_action_logs WHERE command = ?", RefundResolveCommand.NAME);
        assertEquals(1, logs.size(), logs.toString());
        return logs.get(0);
    }

    private String output() {
        return captured.toString(StandardCharsets.UTF_8);
    }
}
