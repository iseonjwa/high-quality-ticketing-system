package com.iseonjwa.payment.ops;

import com.iseonjwa.payment.ops.application.DeadLetterResolveCommand;
import com.iseonjwa.payment.ops.application.OpsApplyContext;
import com.iseonjwa.payment.ops.application.OpsArguments;
import com.iseonjwa.payment.ops.application.OpsPlanHash;
import com.iseonjwa.payment.ops.application.OpsProperties;
import com.iseonjwa.payment.ops.application.OpsRejection;
import com.iseonjwa.payment.ops.application.OpsRunner;
import com.iseonjwa.payment.ops.domain.ManualActionLogRepository;
import com.iseonjwa.payment.ops.domain.OpsInspectionRepository;
import com.iseonjwa.payment.refund.application.DeadLetterReplayService;
import com.iseonjwa.payment.refund.domain.DeadLetterRepository;
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
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * dead-letter resolve의 dry-run·적용·거부를 실제 PostgreSQL로 확인한다(N32). 적용과 거부가 커밋되어야 하므로 테스트
 * 트랜잭션으로 감싸지 않고, 이 범위의 예매 ID로 만든 행과 이 테스트가 만든 {@code dead_letters}를 끝나면 지운다.
 */
@SpringBootTest(properties = {"spring.config.import=", "spring.cloud.config.enabled=false", "eureka.client.enabled=false"})
@ActiveProfiles("test")
class DeadLetterResolveTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired ManualActionLogRepository audit;
    @Autowired OpsInspectionRepository inspection;
    @Autowired DeadLetterRepository deadLetters;
    @Autowired DeadLetterReplayService replay;
    @Autowired OpsProperties properties;
    @Autowired @Qualifier("commonAuditingClock") Clock clock;

    OpsFixtures fixtures;
    DeadLetterResolveCommand command;
    OpsRunner runner;
    ByteArrayOutputStream captured;
    PrintStream out;
    long reservation;
    final List<Long> createdDeadLetters = new ArrayList<>();

    @BeforeEach
    void setUp() {
        cleanUp();
        fixtures = new OpsFixtures(jdbc);
        command = new DeadLetterResolveCommand(deadLetters, inspection, replay);
        runner = new OpsRunner(List.of(command), List.of(), properties, transactionManager, audit, clock);
        captured = new ByteArrayOutputStream();
        out = new PrintStream(captured, true, StandardCharsets.UTF_8);
        reservation = OpsFixtures.nextReservation();
    }

    @AfterEach
    void cleanUp() {
        var range = "reservation_id BETWEEN " + OpsFixtures.FIRST_RESERVATION + " AND " + OpsFixtures.LAST_RESERVATION;
        createdDeadLetters.forEach(id -> jdbc.update("DELETE FROM dead_letters WHERE id = ?", id));
        createdDeadLetters.clear();
        AppendOnlyCleanup.deleteHistories(jdbc, "payment_id IN (SELECT id FROM payments WHERE " + range + ")");
        AppendOnlyCleanup.deleteManualActionLogs(jdbc, "command = ?", DeadLetterResolveCommand.NAME);
        jdbc.update("DELETE FROM refunds WHERE payment_id IN (SELECT id FROM payments WHERE " + range + ")");
        jdbc.update("DELETE FROM payments WHERE " + range);
    }

    @Test
    void N32_replay는_원문을_다시_검증해_환불을_만들고_REPLAYED로_닫는다() {
        var paymentId = fixtures.approvedPayment(reservation);
        var deadLetterId = openDeadLetter(refundRequested(paymentId, 50000));
        var planHash = hash(deadLetterId, "--replay");

        var exit = run("--operator=alice", "--approver=bob", "--dead-letter-id=" + deadLetterId, "--replay", "--apply",
                "--confirm=" + planHash, "--reason=결제 데이터를 고친 뒤 재처리");

        assertEquals(OpsRunner.EXIT_OK, exit, output());
        assertEquals(1, refundCount(paymentId));
        var refund = jdbc.queryForMap("SELECT id, status, source, reason, amount, source_event_id FROM refunds WHERE payment_id = ?", paymentId);
        assertEquals("REQUESTED", refund.get("status"));
        assertEquals("DLQ", refund.get("source"));
        assertEquals("CANCELED", refund.get("reason"));
        var deadLetter = deadLetterRow(deadLetterId);
        assertEquals("RESOLVED", deadLetter.get("status"));
        assertEquals("REPLAYED", deadLetter.get("resolution"));
        assertEquals(refund.get("id"), deadLetter.get("refund_id"));
        assertTrue(deadLetter.get("resolved_at") != null);

        var histories = jdbc.queryForList("SELECT target_type, from_status, to_status, reason, actor FROM payment_status_histories"
                + " WHERE payment_id = ? ORDER BY id", paymentId);
        assertEquals(1, histories.size(), histories.toString());
        assertEquals("REFUND", histories.get(0).get("target_type"));
        assertNull(histories.get(0).get("from_status"));
        assertEquals("REQUESTED", histories.get(0).get("to_status"));
        assertEquals("CANCELED", histories.get(0).get("reason"));
        assertEquals("OPS:alice", histories.get(0).get("actor"));

        var log = onlyLog();
        assertEquals("APPLIED", log.get("result"));
        assertEquals("DEAD_LETTER", log.get("target_type"));
        assertEquals("OPEN", log.get("from_status"));
        assertEquals("REPLAYED", log.get("to_status"));
        assertEquals("bob", log.get("approver"));
        assertEquals(planHash, log.get("plan_hash"));
        assertTrue(output().contains("RESOLVED(REPLAYED)"), output());
    }

    @Test
    void replay는_환불이_이미_있으면_DUPLICATE로_닫고_환불은_한_건_그대로다() {
        var paymentId = fixtures.approvedPayment(reservation);
        var existingRefund = fixtures.refund(paymentId, "REQUESTED");
        var deadLetterId = openDeadLetter(refundRequested(paymentId, 50000));
        var planHash = hash(deadLetterId, "--replay");

        var exit = run("--operator=alice", "--approver=bob", "--dead-letter-id=" + deadLetterId, "--replay", "--apply",
                "--confirm=" + planHash, "--reason=이미 환불이 있다");

        assertEquals(OpsRunner.EXIT_OK, exit, output());
        assertEquals(1, refundCount(paymentId));
        var deadLetter = deadLetterRow(deadLetterId);
        assertEquals("DUPLICATE", deadLetter.get("resolution"));
        assertEquals(existingRefund, deadLetter.get("refund_id"));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM payment_status_histories WHERE payment_id = ?",
                Integer.class, paymentId), "만들지 않은 환불의 이력은 쓰지 않는다");
        assertEquals("DUPLICATE", onlyLog().get("to_status"));
    }

    @Test
    void replay는_검증에_실패하면_거부하고_OPEN을_유지한다() {
        var paymentId = fixtures.approvedPayment(reservation);
        var deadLetterId = openDeadLetter(refundRequested(paymentId, 40000));

        assertEquals(OpsRunner.EXIT_REJECTED, run("--operator=alice", "--dead-letter-id=" + deadLetterId, "--replay"));
        assertTrue(output().contains("amount가 다릅니다"), output());
        assertTrue(output().contains("--no-obligation"), output());

        var exit = run("--operator=alice", "--approver=bob", "--dead-letter-id=" + deadLetterId, "--replay", "--apply",
                "--confirm=" + "0".repeat(64), "--reason=시도");
        assertEquals(OpsRunner.EXIT_REJECTED, exit, output());
        assertStillOpen(deadLetterId);
        assertEquals(0, refundCount(paymentId));
        assertEquals("REJECTED", onlyLog().get("result"));
    }

    @Test
    void replay는_깨진_JSON이면_거부하고_OPEN을_유지한다() {
        var deadLetterId = openDeadLetter("{bad json");

        assertEquals(OpsRunner.EXIT_REJECTED, run("--operator=alice", "--dead-letter-id=" + deadLetterId, "--replay"));

        assertTrue(output().contains("역직렬화·봉투 오류"), output());
        assertStillOpen(deadLetterId);
    }

    @Test
    void replay는_결제가_APPROVED가_아니고_환불도_없으면_거부한다() {
        var paymentId = fixtures.needsManualCheckPayment(reservation, "pg-tx-x", 50000);
        var deadLetterId = openDeadLetter(refundRequested(paymentId, 50000));

        assertEquals(OpsRunner.EXIT_REJECTED, run("--operator=alice", "--dead-letter-id=" + deadLetterId, "--replay"));

        assertTrue(output().contains("환불을 시작할 수 없는 결제 상태입니다"), output());
        assertStillOpen(deadLetterId);
        assertEquals(0, refundCount(paymentId));
    }

    @Test
    void no_obligation은_환불을_만들지_않고_NO_OBLIGATION으로_닫는다() {
        var paymentId = fixtures.approvedPayment(reservation);
        var deadLetterId = openDeadLetter(refundRequested(paymentId, 40000));
        var planHash = hash(deadLetterId, "--no-obligation");

        var exit = run("--operator=alice", "--approver=bob", "--dead-letter-id=" + deadLetterId, "--no-obligation",
                "--apply", "--confirm=" + planHash, "--reason=위조된 요청임을 예매 쪽에서 확인");

        assertEquals(OpsRunner.EXIT_OK, exit, output());
        var deadLetter = deadLetterRow(deadLetterId);
        assertEquals("RESOLVED", deadLetter.get("status"));
        assertEquals("NO_OBLIGATION", deadLetter.get("resolution"));
        assertNull(deadLetter.get("refund_id"));
        assertTrue(deadLetter.get("resolved_at") != null);
        assertEquals(0, refundCount(paymentId));
        var log = onlyLog();
        assertEquals("APPLIED", log.get("result"));
        assertEquals("NO_OBLIGATION", log.get("to_status"));
        assertEquals("위조된 요청임을 예매 쪽에서 확인", log.get("reason"));
        assertNull(log.get("pg_evidence"));
    }

    @Test
    void 플래그가_없거나_둘_다_있으면_사용법_오류다() {
        var deadLetterId = openDeadLetter("{}");

        assertEquals(OpsRunner.EXIT_ERROR, run("--operator=alice", "--dead-letter-id=" + deadLetterId));
        assertTrue(output().contains("사용법 오류"), output());

        assertEquals(OpsRunner.EXIT_ERROR,
                run("--operator=alice", "--dead-letter-id=" + deadLetterId, "--replay", "--no-obligation"));

        assertEquals(OpsRunner.EXIT_ERROR, run("--operator=alice", "--approver=bob", "--dead-letter-id=" + deadLetterId,
                "--apply", "--confirm=" + "0".repeat(64), "--reason=시도"));
        assertStillOpen(deadLetterId);
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM manual_action_logs WHERE command = ?", Integer.class,
                DeadLetterResolveCommand.NAME), "사용법 오류는 감사 기록 대상이 아니다");
    }

    @Test
    void OPEN이_아닌_dead_letters는_거부한다() {
        var deadLetterId = openDeadLetter("{}");
        jdbc.update("UPDATE dead_letters SET status = 'RESOLVED', resolution = 'NO_OBLIGATION', resolved_at = now() WHERE id = ?",
                deadLetterId);

        var exit = run("--operator=alice", "--approver=bob", "--dead-letter-id=" + deadLetterId, "--no-obligation",
                "--apply", "--confirm=" + "0".repeat(64), "--reason=시도");

        assertEquals(OpsRunner.EXIT_REJECTED, exit, output());
        assertTrue(output().contains("현재 상태는 RESOLVED"), output());
        assertEquals("REJECTED", onlyLog().get("result"));
    }

    @Test
    void 승인자가_없으면_거부하고_OPEN을_유지한다() {
        var deadLetterId = openDeadLetter("{}");

        var exit = run("--operator=alice", "--dead-letter-id=" + deadLetterId, "--no-obligation", "--apply",
                "--confirm=" + hash(deadLetterId, "--no-obligation"), "--reason=혼자 시도");

        assertEquals(OpsRunner.EXIT_REJECTED, exit, output());
        assertTrue(output().contains("4-eyes"), output());
        assertStillOpen(deadLetterId);
    }

    @Test
    void 같은_명령을_두_번_적용하면_두_번째는_거부되고_환불은_한_건이다() {
        var paymentId = fixtures.approvedPayment(reservation);
        var deadLetterId = openDeadLetter(refundRequested(paymentId, 50000));
        var planHash = hash(deadLetterId, "--replay");
        var options = new String[] {"--operator=alice", "--approver=bob", "--dead-letter-id=" + deadLetterId, "--replay",
                "--apply", "--confirm=" + planHash, "--reason=재처리"};

        assertEquals(OpsRunner.EXIT_OK, run(options), output());
        assertEquals(OpsRunner.EXIT_REJECTED, run(options), output());

        assertEquals(1, refundCount(paymentId));
        assertEquals("REPLAYED", deadLetterRow(deadLetterId).get("resolution"));
        assertEquals(List.of("APPLIED", "REJECTED"),
                jdbc.queryForList("SELECT result FROM manual_action_logs WHERE command = ? ORDER BY id", String.class,
                        DeadLetterResolveCommand.NAME));
    }

    // 계획 단계의 OPEN 검사를 건너뛴 경쟁 상황이다. 조건부 UPDATE가 0행이면 이미 만든 환불까지 롤백해야 한다
    @Test
    void 이미_닫힌_dead_letters에_같은_계획을_다시_적용하면_조건부_UPDATE가_막고_결과를_바꾸지_않는다() {
        var paymentId = fixtures.approvedPayment(reservation);
        var deadLetterId = openDeadLetter(refundRequested(paymentId, 50000));
        var plan = command.plan(arguments("--dead-letter-id=" + deadLetterId, "--replay"));
        var context = new OpsApplyContext("alice", "bob", Instant.now());
        var transaction = new TransactionTemplate(transactionManager);

        transaction.execute(status -> command.apply(plan, context));
        assertThrows(OpsRejection.class, () -> transaction.execute(status -> command.apply(plan, context)));

        assertEquals(1, refundCount(paymentId));
        assertEquals("REPLAYED", deadLetterRow(deadLetterId).get("resolution"));

        var noObligation = command.plan(arguments("--dead-letter-id=" + openDeadLetter("{}"), "--no-obligation"));
        transaction.execute(status -> command.apply(noObligation, context));
        assertThrows(OpsRejection.class, () -> transaction.execute(status -> command.apply(noObligation, context)));
    }

    private long openDeadLetter(String payload) {
        var id = fixtures.deadLetter(String.valueOf(reservation), payload);
        createdDeadLetters.add(id);
        return id;
    }

    private String refundRequested(long paymentId, long amount) {
        return """
                {"eventId":"%s","eventType":"RefundRequested","version":1,"occurredAt":"2026-11-03T02:00:00.000Z",
                 "producer":"reservation-service","traceId":"abc",
                 "payload":{"reservationId":%d,"paymentId":%d,"userId":7,"amount":%d,"currency":"KRW","reason":"CANCELED"}}"""
                .formatted(UUID.randomUUID(), reservation, paymentId, amount);
    }

    private Map<String, Object> deadLetterRow(long deadLetterId) {
        return jdbc.queryForMap("SELECT * FROM dead_letters WHERE id = ?", deadLetterId);
    }

    private void assertStillOpen(long deadLetterId) {
        var row = deadLetterRow(deadLetterId);
        assertEquals("OPEN", row.get("status"));
        assertNull(row.get("resolution"));
        assertNull(row.get("resolved_at"));
    }

    private int refundCount(long paymentId) {
        return jdbc.queryForObject("SELECT count(*) FROM refunds WHERE payment_id = ?", Integer.class, paymentId);
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
        return new OpsArguments(List.of("ops", "dead-letter", "resolve"), parsed);
    }

    private String hash(long deadLetterId, String mode) {
        return OpsPlanHash.of(DeadLetterResolveCommand.NAME,
                command.plan(arguments("--dead-letter-id=" + deadLetterId, mode)));
    }

    private Map<String, Object> onlyLog() {
        var logs = jdbc.queryForList("SELECT * FROM manual_action_logs WHERE command = ?", DeadLetterResolveCommand.NAME);
        assertEquals(1, logs.size(), logs.toString());
        return logs.get(0);
    }

    private String output() {
        return captured.toString(StandardCharsets.UTF_8);
    }
}
