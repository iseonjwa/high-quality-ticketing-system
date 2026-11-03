package com.iseonjwa.payment.ops;

import com.iseonjwa.payment.ops.application.OpsArguments;
import com.iseonjwa.payment.ops.application.OpsPgLookup;
import com.iseonjwa.payment.ops.application.OpsPlanHash;
import com.iseonjwa.payment.ops.application.OpsProperties;
import com.iseonjwa.payment.ops.application.OpsRunner;
import com.iseonjwa.payment.ops.application.PaymentResolveCommand;
import com.iseonjwa.payment.ops.domain.ManualActionLogRepository;
import com.iseonjwa.payment.ops.domain.OpsInspectionRepository;
import com.iseonjwa.payment.payment.application.PaymentTransitionService;
import com.iseonjwa.payment.payment.domain.DeclineReason;
import com.iseonjwa.payment.pg.application.PgClient.AlreadyApproved;
import com.iseonjwa.payment.pg.application.PgClient.Approved;
import com.iseonjwa.payment.pg.application.PgClient.Declined;
import com.iseonjwa.payment.pg.application.PgClient.NotFound;
import com.iseonjwa.payment.pg.application.PgClient.Prevented;
import com.iseonjwa.payment.pg.application.PgClient.Unknown;
import com.iseonjwa.payment.pg.application.PgPaymentRef;
import com.iseonjwa.payment.pg.application.UnknownCause;
import com.iseonjwa.payment.refund.application.RefundRequestService;
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
 * payment resolve의 dry-run·적용·거부를 실제 PostgreSQL과 가짜 PgClient로 확인한다(N21). 적용과 거부가 커밋되어야 하므로
 * 테스트 트랜잭션으로 감싸지 않고 이 범위의 예매 ID로 만든 행을 끝나면 지운다.
 */
@SpringBootTest(properties = {"spring.config.import=", "spring.cloud.config.enabled=false", "eureka.client.enabled=false"})
@ActiveProfiles("test")
class PaymentResolveTest {
    private static final Instant PG_APPROVED_AT = Instant.parse("2026-11-03T01:00:00Z");

    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired ManualActionLogRepository audit;
    @Autowired OpsInspectionRepository inspection;
    @Autowired PaymentTransitionService transitions;
    @Autowired RefundRequestService refundRequests;
    @Autowired OpsProperties properties;
    @Autowired @Qualifier("commonAuditingClock") Clock clock;

    final ScriptedPgClient pg = new ScriptedPgClient();
    OpsFixtures fixtures;
    PaymentResolveCommand command;
    OpsRunner runner;
    ByteArrayOutputStream captured;
    PrintStream out;
    long reservation;

    @BeforeEach
    void setUp() {
        cleanUp();
        fixtures = new OpsFixtures(jdbc);
        command = new PaymentResolveCommand(inspection, new OpsPgLookup(pg.asProvider()), transitions, refundRequests);
        runner = new OpsRunner(List.of(command), List.of(), properties, transactionManager, audit, clock);
        captured = new ByteArrayOutputStream();
        out = new PrintStream(captured, true, StandardCharsets.UTF_8);
        reservation = OpsFixtures.nextReservation();
    }

    @AfterEach
    void cleanUp() {
        var range = "reservation_id BETWEEN " + OpsFixtures.FIRST_RESERVATION + " AND " + OpsFixtures.LAST_RESERVATION;
        AppendOnlyCleanup.deleteHistories(jdbc, "payment_id IN (SELECT id FROM payments WHERE " + range + ")");
        AppendOnlyCleanup.deleteManualActionLogs(jdbc, "command = ?", PaymentResolveCommand.NAME);
        jdbc.update("DELETE FROM refunds WHERE payment_id IN (SELECT id FROM payments WHERE " + range + ")");
        jdbc.update("DELETE FROM payments WHERE " + range);
        jdbc.update("DELETE FROM outbox_events WHERE message_key ~ '^91[0-9]{5}$'");
    }

    @Test
    void N21_PG가_승인했고_금액이_같으면_APPROVED로_옮기고_PaymentApproved와_이력과_감사를_한_번에_남긴다() {
        var paymentId = fixtures.needsManualCheckPayment(reservation, null, null);
        pg.payment = approved("pg-tx-1", 50000);
        var planHash = hash(paymentId);

        var exit = run("--operator=alice", "--approver=bob", "--payment-id=" + paymentId, "--apply",
                "--confirm=" + planHash, "--reason=PG 콘솔에서 승인 확인");

        assertEquals(OpsRunner.EXIT_OK, exit, output());
        var payment = jdbc.queryForMap("SELECT status, pg_tx_id, pg_approved_amount, approved_at FROM payments WHERE id = ?",
                paymentId);
        assertEquals("APPROVED", payment.get("status"));
        assertEquals("pg-tx-1", payment.get("pg_tx_id"));
        assertEquals(PG_APPROVED_AT, ((java.sql.Timestamp) payment.get("approved_at")).toInstant());
        assertEquals(1, outboxCount("PaymentApproved"));
        assertEquals(0, outboxCount("PaymentRefunded"));

        var history = onlyPaymentHistory(paymentId);
        assertEquals("NEEDS_MANUAL_CHECK", history.get("from_status"));
        assertEquals("APPROVED", history.get("to_status"));
        assertEquals("OPS:alice", history.get("actor"));

        var log = onlyLog();
        assertEquals("APPLIED", log.get("result"));
        assertEquals("payment resolve", log.get("command"));
        assertEquals("PAYMENT", log.get("target_type"));
        assertEquals("NEEDS_MANUAL_CHECK", log.get("from_status"));
        assertEquals("APPROVED", log.get("to_status"));
        assertEquals(planHash, log.get("plan_hash"));
        assertEquals("alice", log.get("operator"));
        assertEquals("bob", log.get("approver"));
        // JSONB는 키 순서를 바꿔 저장하므로 필드별로 본다. 카드 정보 같은 값은 근거에 없다
        var evidence = log.get("pg_evidence").toString();
        assertTrue(evidence.contains("\"lookup\": \"Approved\"") && evidence.contains("\"pgTxId\": \"pg-tx-1\"")
                && evidence.contains("\"amount\": \"50000\"") && evidence.contains("\"currency\": \"KRW\""), evidence);
        assertEquals(0, pg.preventLateCalls);
        assertEquals(0, pg.refundCalls);
    }

    @Test
    void PG_승인_금액이_다르면_PG_승인_금액으로_환불_REQUESTED를_만들고_결제는_NMC로_둔다() {
        var paymentId = fixtures.needsManualCheckPayment(reservation, null, null);
        pg.payment = approved("pg-tx-2", 49000);

        var exit = run("--operator=alice", "--approver=bob", "--payment-id=" + paymentId, "--apply",
                "--confirm=" + hash(paymentId), "--reason=금액 불일치 환불");

        assertEquals(OpsRunner.EXIT_OK, exit, output());
        var refund = jdbc.queryForMap("SELECT amount, reason, source, status, next_check_at FROM refunds WHERE payment_id = ?",
                paymentId);
        assertEquals(0, new BigDecimal("49000").compareTo((BigDecimal) refund.get("amount")));
        assertEquals("PG_AMOUNT_MISMATCH", refund.get("reason"));
        assertEquals("OPS", refund.get("source"));
        assertEquals("REQUESTED", refund.get("status"));
        assertTrue(refund.get("next_check_at") != null, "환불 워커가 집을 수 있어야 한다");

        // 환불 워커가 환불할 수 있도록 PG 거래 식별자와 승인 금액이 결제에 남는다
        var payment = jdbc.queryForMap("SELECT status, pg_tx_id, pg_approved_amount FROM payments WHERE id = ?", paymentId);
        assertEquals("NEEDS_MANUAL_CHECK", payment.get("status"));
        assertEquals("pg-tx-2", payment.get("pg_tx_id"));
        assertEquals(0, new BigDecimal("49000").compareTo((BigDecimal) payment.get("pg_approved_amount")));

        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE message_key = ?", Integer.class,
                String.valueOf(reservation)), "환불 시작은 이벤트를 내지 않는다");
        var history = jdbc.queryForMap("SELECT target_type, from_status, to_status, reason, actor FROM payment_status_histories"
                + " WHERE payment_id = ?", paymentId);
        assertEquals("REFUND", history.get("target_type"));
        assertNull(history.get("from_status"));
        assertEquals("REQUESTED", history.get("to_status"));
        assertEquals("PG_AMOUNT_MISMATCH", history.get("reason"));
        assertEquals("OPS:alice", history.get("actor"));
        var log = onlyLog();
        assertEquals("APPLIED", log.get("result"));
        assertEquals("NEEDS_MANUAL_CHECK", log.get("to_status"));
        assertEquals(0, pg.refundCalls, "Ops 명령은 PG 환불을 부르지 않는다");
    }

    @Test
    void 금액_불일치인데_이미_환불_행이_있으면_거부한다() {
        var paymentId = fixtures.needsManualCheckPayment(reservation, "pg-tx-3", 49000);
        fixtures.refund(paymentId, "REQUESTED");
        pg.payment = approved("pg-tx-3", 49000);

        assertEquals(OpsRunner.EXIT_REJECTED, run("--operator=alice", "--approver=bob", "--payment-id=" + paymentId,
                "--apply", "--confirm=" + "a".repeat(64), "--reason=시도"));

        assertTrue(output().contains("이미 환불 행이 있습니다"), output());
        assertEquals("REJECTED", onlyLog().get("result"));
    }

    @Test
    void PG가_거절했으면_DECLINED로_옮기고_PaymentDeclined를_남긴다() {
        var paymentId = fixtures.needsManualCheckPayment(reservation, null, null);
        pg.payment = new Declined(DeclineReason.CARD_DECLINED);

        var exit = run("--operator=alice", "--approver=bob", "--payment-id=" + paymentId, "--apply",
                "--confirm=" + hash(paymentId), "--reason=PG 거절 확인");

        assertEquals(OpsRunner.EXIT_OK, exit, output());
        assertEquals("DECLINED", status(paymentId));
        assertEquals("CARD_DECLINED", jdbc.queryForObject("SELECT decline_code FROM payments WHERE id = ?", String.class, paymentId));
        assertEquals(1, outboxCount("PaymentDeclined"));
        assertEquals("OPS:alice", onlyPaymentHistory(paymentId).get("actor"));
        assertEquals("DECLINED", onlyLog().get("to_status"));
    }

    @Test
    void PG가_주문을_못_찾으면_dry_run은_늦은_승인_방지를_부르지_않고_적용_때만_불러_DECLINED_NOT_SENT로_옮긴다() {
        var paymentId = fixtures.needsManualCheckPayment(reservation, null, null);
        pg.payment = new NotFound();
        pg.lateApproval = new Prevented();

        assertEquals(OpsRunner.EXIT_OK, run("--operator=alice", "--payment-id=" + paymentId));
        assertEquals(0, pg.preventLateCalls, "dry-run은 PG에 흔적을 남기는 호출을 하지 않는다");
        assertEquals("NEEDS_MANUAL_CHECK", status(paymentId));
        assertTrue(output().contains("늦은 승인 방지"), output());

        var exit = run("--operator=alice", "--approver=bob", "--payment-id=" + paymentId, "--apply",
                "--confirm=" + hash(paymentId), "--reason=PG에 주문이 없다");

        assertEquals(OpsRunner.EXIT_OK, exit, output());
        assertEquals(1, pg.preventLateCalls);
        assertEquals("DECLINED", status(paymentId));
        assertEquals("NOT_SENT", jdbc.queryForObject("SELECT decline_code FROM payments WHERE id = ?", String.class, paymentId));
        assertEquals(1, outboxCount("PaymentDeclined"));
        var log = onlyLog();
        assertEquals("DECLINED", log.get("to_status"));
        assertTrue(log.get("pg_evidence").toString().contains("\"lateApproval\": \"Prevented\""), log.toString());
    }

    @Test
    void 방지가_이미_승인됐다고_알리면_금액을_대조해_APPROVED로_옮기고_감사에는_실제_전이를_남긴다() {
        var paymentId = fixtures.needsManualCheckPayment(reservation, null, null);
        pg.payment = new NotFound();
        pg.lateApproval = new AlreadyApproved(new PgPaymentRef("pg-tx-late"), new BigDecimal("50000"), "KRW", PG_APPROVED_AT);

        var exit = run("--operator=alice", "--approver=bob", "--payment-id=" + paymentId, "--apply",
                "--confirm=" + hash(paymentId), "--reason=늦은 승인 확인");

        assertEquals(OpsRunner.EXIT_OK, exit, output());
        assertEquals("APPROVED", status(paymentId));
        assertEquals(1, outboxCount("PaymentApproved"));
        var log = onlyLog();
        assertEquals("APPROVED", log.get("to_status"));
        assertTrue(log.get("pg_evidence").toString().contains("\"lateApproval\": \"AlreadyApproved\""), log.toString());
    }

    @Test
    void 방지가_이미_승인됐는데_금액이_다르면_환불을_시작한다() {
        var paymentId = fixtures.needsManualCheckPayment(reservation, null, null);
        pg.payment = new NotFound();
        pg.lateApproval = new AlreadyApproved(new PgPaymentRef("pg-tx-late"), new BigDecimal("48000"), "KRW", PG_APPROVED_AT);

        var exit = run("--operator=alice", "--approver=bob", "--payment-id=" + paymentId, "--apply",
                "--confirm=" + hash(paymentId), "--reason=늦은 승인 금액 불일치");

        assertEquals(OpsRunner.EXIT_OK, exit, output());
        assertEquals("NEEDS_MANUAL_CHECK", status(paymentId));
        assertEquals("REQUESTED", jdbc.queryForObject("SELECT status FROM refunds WHERE payment_id = ?", String.class, paymentId));
    }

    @Test
    void PG_결과를_모르면_거부하고_상태를_유지하며_REJECTED만_남긴다() {
        var paymentId = fixtures.needsManualCheckPayment(reservation, null, null);
        pg.payment = new Unknown(UnknownCause.TIMEOUT);

        assertEquals(OpsRunner.EXIT_REJECTED, run("--operator=alice", "--payment-id=" + paymentId));
        assertEquals(OpsRunner.EXIT_REJECTED, run("--operator=alice", "--approver=bob", "--payment-id=" + paymentId,
                "--apply", "--confirm=" + "b".repeat(64), "--reason=시도"));

        assertEquals("NEEDS_MANUAL_CHECK", status(paymentId));
        assertEquals(0, outboxCount("PaymentApproved") + outboxCount("PaymentDeclined"));
        assertEquals("REJECTED", onlyLog().get("result"));
        assertEquals(0, pg.preventLateCalls);
    }

    @Test
    void PG_조회가_예외를_던져도_결과를_모르는_것으로_보고_거부한다() {
        var paymentId = fixtures.needsManualCheckPayment(reservation, null, null);
        pg.failure = new IllegalStateException("연결 실패");

        assertEquals(OpsRunner.EXIT_REJECTED, run("--operator=alice", "--payment-id=" + paymentId));
        assertEquals("NEEDS_MANUAL_CHECK", status(paymentId));
    }

    @Test
    void NEEDS_MANUAL_CHECK가_아닌_결제는_거부한다() {
        var paymentId = fixtures.approvedPayment(reservation);
        pg.payment = approved("pg-tx-x", 50000);

        assertEquals(OpsRunner.EXIT_REJECTED, run("--operator=alice", "--approver=bob", "--payment-id=" + paymentId,
                "--apply", "--confirm=" + "c".repeat(64), "--reason=시도"));

        assertTrue(output().contains("NEEDS_MANUAL_CHECK인 결제만"), output());
        assertEquals("APPROVED", status(paymentId));
        assertEquals(0, pg.findPaymentCalls, "대상이 맞지 않으면 PG를 조회하지 않는다");
        assertEquals("REJECTED", onlyLog().get("result"));
    }

    @Test
    void 결제에_기록된_PG_거래_식별자가_PG_결과와_다르면_거부한다() {
        var paymentId = fixtures.needsManualCheckPayment(reservation, "pg-tx-recorded", 49000);
        pg.payment = approved("pg-tx-other", 49000);

        assertEquals(OpsRunner.EXIT_REJECTED, run("--operator=alice", "--payment-id=" + paymentId));

        assertTrue(output().contains("PG 거래 식별자가 PG의 것과 다릅니다"), output());
        assertEquals("NEEDS_MANUAL_CHECK", status(paymentId));
    }

    @Test
    void dry_run_뒤_PG_결과가_바뀌면_해시가_달라져_거부한다() {
        var paymentId = fixtures.needsManualCheckPayment(reservation, null, null);
        pg.payment = approved("pg-tx-4", 50000);
        var stale = hash(paymentId);
        pg.payment = new Declined(DeclineReason.CARD_DECLINED);

        var exit = run("--operator=alice", "--approver=bob", "--payment-id=" + paymentId, "--apply",
                "--confirm=" + stale, "--reason=시도");

        assertEquals(OpsRunner.EXIT_REJECTED, exit);
        assertTrue(output().contains("계획 해시가 확인 값과 다릅니다"), output());
        assertEquals("NEEDS_MANUAL_CHECK", status(paymentId));
        assertEquals(0, outboxCount("PaymentApproved") + outboxCount("PaymentDeclined"));
        assertEquals("REJECTED", onlyLog().get("result"));
    }

    @Test
    void 같은_명령을_두_번_적용하면_두_번째는_거부하고_이벤트는_한_건이다() {
        var paymentId = fixtures.needsManualCheckPayment(reservation, null, null);
        pg.payment = approved("pg-tx-5", 50000);
        var planHash = hash(paymentId);
        var arguments = new String[] {"--operator=alice", "--approver=bob", "--payment-id=" + paymentId, "--apply",
                "--confirm=" + planHash, "--reason=적용"};

        assertEquals(OpsRunner.EXIT_OK, run(arguments));
        assertEquals(OpsRunner.EXIT_REJECTED, run(arguments));

        assertEquals(1, outboxCount("PaymentApproved"));
        assertEquals("APPROVED", status(paymentId));
        var logs = jdbc.queryForList("SELECT result FROM manual_action_logs WHERE command = ? ORDER BY id",
                PaymentResolveCommand.NAME);
        assertEquals(List.of("APPLIED", "REJECTED"), logs.stream().map(row -> row.get("result")).toList());
    }

    @Test
    void 승인자가_없으면_PG를_조회하기_전에_거부한다() {
        var paymentId = fixtures.needsManualCheckPayment(reservation, null, null);
        pg.payment = approved("pg-tx-6", 50000);

        assertEquals(OpsRunner.EXIT_REJECTED, run("--operator=alice", "--payment-id=" + paymentId, "--apply",
                "--confirm=" + "d".repeat(64), "--reason=시도"));

        assertEquals(0, pg.findPaymentCalls);
        assertEquals("NEEDS_MANUAL_CHECK", status(paymentId));
    }

    private Approved approved(String pgTxId, int amount) {
        return new Approved(new PgPaymentRef(pgTxId), new BigDecimal(amount), "KRW", PG_APPROVED_AT);
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
        return new OpsArguments(List.of("ops", "payment", "resolve"), parsed);
    }

    private String hash(long paymentId) {
        return OpsPlanHash.of(PaymentResolveCommand.NAME, command.plan(arguments("--payment-id=" + paymentId)));
    }

    private String status(long paymentId) {
        return jdbc.queryForObject("SELECT status FROM payments WHERE id = ?", String.class, paymentId);
    }

    private int outboxCount(String eventType) {
        return jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE message_key = ? AND event_type = ?",
                Integer.class, String.valueOf(reservation), eventType);
    }

    private Map<String, Object> onlyPaymentHistory(long paymentId) {
        var rows = jdbc.queryForList("SELECT * FROM payment_status_histories WHERE payment_id = ? AND target_type = 'PAYMENT'",
                paymentId);
        assertEquals(1, rows.size(), rows.toString());
        return rows.get(0);
    }

    private Map<String, Object> onlyLog() {
        var logs = jdbc.queryForList("SELECT * FROM manual_action_logs WHERE command = ?", PaymentResolveCommand.NAME);
        assertEquals(1, logs.size(), logs.toString());
        return logs.get(0);
    }

    private String output() {
        return captured.toString(StandardCharsets.UTF_8);
    }
}
