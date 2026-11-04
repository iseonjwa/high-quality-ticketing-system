package com.iseonjwa.payment.ops;

import com.iseonjwa.common.messaging.outbox.OutboxBackoff;
import com.iseonjwa.common.messaging.outbox.OutboxEventSender;
import com.iseonjwa.common.messaging.outbox.OutboxProperties;
import com.iseonjwa.common.messaging.outbox.OutboxRelay;
import com.iseonjwa.common.messaging.outbox.OutboxRepository;
import com.iseonjwa.payment.ops.application.InspectQuery;
import com.iseonjwa.payment.ops.application.OpsApplyContext;
import com.iseonjwa.payment.ops.application.OpsArguments;
import com.iseonjwa.payment.ops.application.OpsPlanHash;
import com.iseonjwa.payment.ops.application.OpsProperties;
import com.iseonjwa.payment.ops.application.OpsRejection;
import com.iseonjwa.payment.ops.application.OpsRunner;
import com.iseonjwa.payment.ops.application.OutboxRequeueCommand;
import com.iseonjwa.payment.ops.domain.ManualActionLogRepository;
import com.iseonjwa.payment.ops.domain.OpsInspectionRepository;
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
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * outbox requeue의 dry-run·적용·거부를 실제 DB로 확인한다. 적용과 거부가 커밋되어야 하므로 테스트 트랜잭션으로 감싸지
 * 않고 이 범위의 키(예매 ID 문자열)로 만든 행을 끝나면 지운다.
 */
@SpringBootTest(properties = {"spring.config.import=", "spring.cloud.config.enabled=false", "eureka.client.enabled=false"})
@ActiveProfiles("test")
class OutboxRequeueTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired ManualActionLogRepository audit;
    @Autowired OpsInspectionRepository inspection;
    @Autowired OutboxRepository outboxRepository;
    @Autowired OutboxProperties outboxProperties;
    @Autowired OpsProperties properties;
    @Autowired @Qualifier("commonAuditingClock") Clock clock;

    OpsFixtures fixtures;
    OutboxRequeueCommand command;
    OpsRunner runner;
    ByteArrayOutputStream captured;
    PrintStream out;
    String key;

    @BeforeEach
    void setUp() {
        cleanUp();
        fixtures = new OpsFixtures(jdbc);
        command = new OutboxRequeueCommand(inspection, outboxRepository);
        runner = new OpsRunner(List.of(command), List.of(), properties, transactionManager, audit, clock);
        captured = new ByteArrayOutputStream();
        out = new PrintStream(captured, true, StandardCharsets.UTF_8);
        key = String.valueOf(OpsFixtures.nextReservation());
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM outbox_events WHERE message_key ~ '^91[0-9]{5}$'");
        AppendOnlyCleanup.deleteManualActionLogs(jdbc, "command = ?", OutboxRequeueCommand.NAME);
    }

    @Test
    void dry_run은_이벤트와_오류와_뒤에_막힌_행_수를_보이고_아무것도_바꾸지_않는다() {
        var failed = fixtures.outbox(key, "PaymentApproved", "FAILED", 5, "직렬화 실패");
        fixtures.outbox(key, "PaymentRefunded", "PENDING", 0, null);
        fixtures.outbox(key, "PaymentRefunded", "PENDING", 0, null);

        var exit = run("--operator=alice", "--outbox-id=" + failed);

        assertEquals(OpsRunner.EXIT_OK, exit);
        var output = output();
        assertTrue(output.contains("이벤트: PaymentApproved 토픽=payment.result 키=" + key), output);
        assertTrue(output.contains("마지막 오류: 직렬화 실패"), output);
        assertTrue(output.contains("뒤에 막혀 있는 PENDING: 2건"), output);
        assertTrue(output.contains("바뀔 전이: FAILED -> PENDING"), output);
        assertTrue(output.contains("계획 해시: " + hash(failed)), output);
        assertEquals("FAILED", status(failed));
        assertEquals(0, logCount());
    }

    @Test
    void 앞에_다른_FAILED가_있으면_주의를_보인다() {
        fixtures.outbox(key, "PaymentApproved", "FAILED", 5, "e1");
        var second = fixtures.outbox(key, "PaymentRefunded", "FAILED", 5, "e2");

        run("--operator=alice", "--outbox-id=" + second);

        assertTrue(output().contains("앞에 있는 다른 FAILED가 1건"), output());
    }

    @Test
    void 적용하면_PENDING으로_되돌리고_attempts와_last_error는_남기고_APPLIED를_기록한다() {
        var failed = fixtures.outbox(key, "PaymentApproved", "FAILED", 5, "직렬화 실패");
        var planHash = hash(failed);
        var before = Instant.now();

        var exit = run("--operator=alice", "--outbox-id=" + failed, "--apply", "--confirm=" + planHash,
                "--reason=직렬화 설정을 고쳤다");

        assertEquals(OpsRunner.EXIT_OK, exit, output());
        var row = jdbc.queryForMap("SELECT status, attempts, last_error, claimed_until, next_attempt_at FROM outbox_events WHERE id = ?",
                failed);
        assertEquals("PENDING", row.get("status"));
        assertEquals(5, ((Number) row.get("attempts")).intValue());
        assertEquals("직렬화 실패", row.get("last_error"));
        assertNull(row.get("claimed_until"));
        assertFalse(((java.sql.Timestamp) row.get("next_attempt_at")).toInstant().isAfter(Instant.now()));
        assertTrue(((java.sql.Timestamp) row.get("next_attempt_at")).toInstant().isAfter(before.minusSeconds(60)));

        var log = onlyLog();
        assertEquals("APPLIED", log.get("result"));
        assertEquals("outbox requeue", log.get("command"));
        assertEquals("OUTBOX", log.get("target_type"));
        assertEquals(failed, ((Number) log.get("target_id")).longValue());
        assertEquals("FAILED", log.get("from_status"));
        assertEquals("PENDING", log.get("to_status"));
        assertEquals(planHash, log.get("plan_hash"));
        assertEquals("alice", log.get("operator"));
        assertNull(log.get("approver"));
        assertEquals("직렬화 설정을 고쳤다", log.get("reason"));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM payment_status_histories h JOIN payments p ON p.id = h.payment_id"
                + " WHERE h.actor = 'OPS:alice'", Integer.class), "결제 전이가 아니므로 상태 이력을 쓰지 않는다");
    }

    @Test
    void 적용한_뒤_릴레이가_같은_키의_뒤_행까지_순서대로_발행한다() {
        var first = fixtures.outbox(key, "PaymentApproved", "FAILED", 5, "직렬화 실패");
        var second = fixtures.outbox(key, "PaymentRefunded", "PENDING", 0, null);
        // 요청한 행은 아직 집을 수 없고 뒤 행도 FAILED 때문에 막혀 있다
        var sent = new ArrayList<String>();
        var relay = relay(sent);
        relay.relayOnce();
        assertEquals(List.of(), sent, "FAILED가 같은 키의 뒤 행을 막고 있다");
        // 시험 대상 아닌 행이 먼저 PENDING으로 보이는 것을 막으려고 next_attempt_at/임대를 지금 기준으로 맞춘다
        jdbc.update("UPDATE outbox_events SET next_attempt_at = now() - interval '1 minute', claimed_until = NULL WHERE id = ?", second);

        var exit = run("--operator=alice", "--outbox-id=" + first, "--apply", "--confirm=" + hash(first),
                "--reason=되살린다");
        assertEquals(OpsRunner.EXIT_OK, exit, output());
        for (var i = 0; i < 6 && sent.size() < 2; i++) {
            relay.relayOnce();
        }

        assertEquals(List.of("PaymentApproved", "PaymentRefunded"),
                sent.stream().filter(entry -> entry.startsWith(key + ":")).map(entry -> entry.substring(key.length() + 1)).toList());
        assertEquals("SENT", status(first));
        assertEquals("SENT", status(second));
    }

    @Test
    void FAILED가_아닌_행은_거부하고_REJECTED를_기록하며_행을_바꾸지_않는다() {
        var pending = fixtures.outbox(key, "PaymentApproved", "PENDING", 0, null);
        var sentRow = fixtures.outbox(key, "PaymentApproved", "SENT", 1, null);

        assertEquals(OpsRunner.EXIT_REJECTED, run("--operator=alice", "--outbox-id=" + pending, "--apply",
                "--confirm=" + "a".repeat(64), "--reason=시도"));
        assertEquals(OpsRunner.EXIT_REJECTED, run("--operator=alice", "--outbox-id=" + sentRow));

        assertEquals("PENDING", status(pending));
        assertEquals("SENT", status(sentRow));
        var log = onlyLog();
        assertEquals("REJECTED", log.get("result"));
        assertTrue(log.get("reason").toString().contains("FAILED인 행만"), log.toString());
        assertEquals("a".repeat(64), log.get("plan_hash"));
    }

    @Test
    void 없는_행은_거부한다() {
        assertEquals(OpsRunner.EXIT_REJECTED, run("--operator=alice", "--outbox-id=999999999"));
        assertTrue(output().contains("Outbox 행이 없습니다"), output());
    }

    @Test
    void dry_run_뒤_행이_바뀌어_해시가_다르면_거부한다() {
        var failed = fixtures.outbox(key, "PaymentApproved", "FAILED", 5, "직렬화 실패");
        var stale = hash(failed);
        jdbc.update("UPDATE outbox_events SET last_error = '다른 오류' WHERE id = ?", failed);

        var exit = run("--operator=alice", "--outbox-id=" + failed, "--apply", "--confirm=" + stale, "--reason=시도");

        assertEquals(OpsRunner.EXIT_REJECTED, exit);
        assertEquals("FAILED", status(failed));
        assertEquals("REJECTED", onlyLog().get("result"));
    }

    @Test
    void 조건부_UPDATE는_계획_뒤_FAILED가_아니게_된_행을_바꾸지_않는다() {
        var failed = fixtures.outbox(key, "PaymentApproved", "FAILED", 5, "직렬화 실패");
        var plan = command.plan(arguments("--outbox-id=" + failed));
        // 계획을 만든 뒤 다른 경로가 행을 이미 처리했다
        jdbc.update("UPDATE outbox_events SET status = 'SENT', sent_at = now() WHERE id = ?", failed);

        assertThrows(OpsRejection.class,
                () -> command.apply(plan, new OpsApplyContext("alice", null, Instant.now())));
        assertEquals("SENT", status(failed));
        assertFalse(outboxRepository.requeueFailed(failed, Instant.now()));
        assertEquals("SENT", status(failed));
    }

    @Test
    void outbox_id가_없으면_사용법_오류다() {
        assertEquals(OpsRunner.EXIT_ERROR, run("--operator=alice"));
    }

    private OutboxRelay relay(List<String> sent) {
        OutboxEventSender sender = (topic, messageKey, payload) -> {
            // 이 범위의 키만 기록한다. 같은 테이블의 다른 행이 함께 집혀도 단언에 영향을 주지 않는다
            if (messageKey.equals(key)) {
                sent.add(messageKey + ":" + payload.replaceAll(".*\"eventType\": ?\"([A-Za-z]+)\".*", "$1"));
            }
            return CompletableFuture.completedFuture(null);
        };
        return new OutboxRelay(outboxRepository, sender, clock, outboxProperties, OutboxBackoff.withRandomJitter());
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
        return new OpsArguments(List.of("ops", "outbox", "requeue"), parsed);
    }

    private String hash(long outboxId) {
        return OpsPlanHash.of(OutboxRequeueCommand.NAME, command.plan(arguments("--outbox-id=" + outboxId)));
    }

    private String status(long outboxId) {
        return jdbc.queryForObject("SELECT status FROM outbox_events WHERE id = ?", String.class, outboxId);
    }

    private String output() {
        return captured.toString(StandardCharsets.UTF_8);
    }

    private int logCount() {
        return jdbc.queryForObject("SELECT count(*) FROM manual_action_logs WHERE command = ?", Integer.class,
                OutboxRequeueCommand.NAME);
    }

    private Map<String, Object> onlyLog() {
        var logs = jdbc.queryForList("SELECT * FROM manual_action_logs WHERE command = ?", OutboxRequeueCommand.NAME);
        assertEquals(1, logs.size(), logs.toString());
        assertNotNull(logs.get(0).get("performed_at"));
        return logs.get(0);
    }
}
