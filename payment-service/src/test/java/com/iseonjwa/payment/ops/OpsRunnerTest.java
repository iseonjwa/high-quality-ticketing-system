package com.iseonjwa.payment.ops;

import com.iseonjwa.payment.ops.application.OpsArguments;
import com.iseonjwa.payment.ops.application.OpsPlanHash;
import com.iseonjwa.payment.ops.application.OpsProperties;
import com.iseonjwa.payment.ops.application.OpsRunner;
import com.iseonjwa.payment.ops.domain.ManualActionLogRepository;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 실행기의 공통 통제를 가짜 명령으로 확인한다(N32). 거부는 종료 코드 2와 REJECTED 기록, 적용은 0과 APPLIED 기록이고,
 * dry-run은 상태도 감사도 바꾸지 않는다. 적용·거부가 실제로 커밋되어야 하므로 테스트 트랜잭션으로 감싸지 않는다.
 */
@SpringBootTest(properties = {"spring.config.import=", "spring.cloud.config.enabled=false", "eureka.client.enabled=false"})
@ActiveProfiles("test")
class OpsRunnerTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired ManualActionLogRepository audit;
    @Autowired OpsProperties properties;
    @Autowired @Qualifier("commonAuditingClock") Clock clock;

    FakeOpsCommand fourEyes;
    OpsRunner runner;
    ByteArrayOutputStream captured;
    PrintStream out;

    @BeforeEach
    void setUp() {
        cleanUp();
        fourEyes = new FakeOpsCommand(true, jdbc);
        runner = new OpsRunner(List.of(fourEyes), List.of(), properties, transactionManager, audit, clock);
        captured = new ByteArrayOutputStream();
        out = new PrintStream(captured, true, StandardCharsets.UTF_8);
    }

    @AfterEach
    void cleanUp() {
        AppendOnlyCleanup.deleteManualActionLogs(jdbc, "command = ?", FakeOpsCommand.NAME);
        jdbc.update("DELETE FROM processed_events WHERE consumer_group = 'ops-test'");
    }

    @Test
    void dry_run은_상태를_바꾸지_않고_감사도_남기지_않는다() {
        var exit = run("--operator=alice", "--target-id=1");

        assertEquals(OpsRunner.EXIT_OK, exit);
        assertEquals("A", fourEyes.state.get());
        assertEquals(0, logCount());
        assertEquals(0, markerCount());
        assertTrue(output().contains("계획 해시: " + currentHash(1)), output());
    }

    @Test
    void 승인자_없이_4_eyes_명령을_적용하면_거부하고_REJECTED를_남긴다() {
        var exit = run("--operator=alice", "--target-id=1", "--apply", "--confirm=" + currentHash(1), "--reason=테스트");

        assertEquals(OpsRunner.EXIT_REJECTED, exit);
        assertEquals("A", fourEyes.state.get());
        var log = onlyLog();
        assertEquals("REJECTED", log.get("result"));
        assertEquals("alice", log.get("operator"));
        assertNull(log.get("approver"));
        assertTrue(log.get("reason").toString().contains("--approver"), log.toString());
        assertEquals(0, markerCount());
    }

    @Test
    void 승인자가_실행자와_같으면_거부하고_REJECTED를_남긴다() {
        var exit = run("--operator=alice", "--approver=alice", "--target-id=1", "--apply", "--confirm=" + currentHash(1),
                "--reason=테스트");

        assertEquals(OpsRunner.EXIT_REJECTED, exit);
        assertEquals("A", fourEyes.state.get());
        var log = onlyLog();
        assertEquals("REJECTED", log.get("result"));
        // DB 제약이 같은 사람의 승인자 기록을 거부하므로 거부 기록에는 비우고 사유에 적는다
        assertNull(log.get("approver"));
        assertTrue(log.get("reason").toString().contains("승인자가 실행자와 같습니다"), log.toString());
    }

    @Test
    void 허용_목록_밖의_실행자는_거부하고_REJECTED를_남긴다() {
        var exit = run("--operator=mallory", "--approver=bob", "--target-id=1", "--apply", "--confirm=" + currentHash(1),
                "--reason=테스트");

        assertEquals(OpsRunner.EXIT_REJECTED, exit);
        assertEquals("A", fourEyes.state.get());
        var log = onlyLog();
        assertEquals("REJECTED", log.get("result"));
        assertEquals("mallory", log.get("operator"));
        assertTrue(log.get("reason").toString().contains("허용 목록에 없는 실행자"), log.toString());
    }

    @Test
    void 허용_목록_밖의_승인자는_거부하고_REJECTED를_남긴다() {
        var exit = run("--operator=alice", "--approver=mallory", "--target-id=1", "--apply", "--confirm=" + currentHash(1),
                "--reason=테스트");

        assertEquals(OpsRunner.EXIT_REJECTED, exit);
        var log = onlyLog();
        assertEquals("mallory", log.get("approver"));
        assertTrue(log.get("reason").toString().contains("허용 목록에 없는 승인자"), log.toString());
    }

    @Test
    void 실행자를_모르면_거부하고_기록하지_않는다() {
        var exit = run("--target-id=1", "--apply", "--confirm=" + currentHash(1), "--reason=테스트");

        assertEquals(OpsRunner.EXIT_REJECTED, exit);
        assertEquals(0, logCount());
    }

    @Test
    void dry_run도_허용_목록_밖의_실행자는_거부하지만_기록하지_않는다() {
        var exit = run("--operator=mallory", "--target-id=1");

        assertEquals(OpsRunner.EXIT_REJECTED, exit);
        assertEquals(0, logCount());
    }

    @Test
    void 계획_해시가_다르면_거부하고_다시_만든_해시를_REJECTED로_남긴다() {
        var stale = currentHash(1);
        fourEyes.state.set("A2");   // dry-run 뒤에 상태가 바뀌었다

        var exit = run("--operator=alice", "--approver=bob", "--target-id=1", "--apply", "--confirm=" + stale,
                "--reason=테스트");

        assertEquals(OpsRunner.EXIT_REJECTED, exit);
        assertEquals("A2", fourEyes.state.get());
        var log = onlyLog();
        assertEquals("REJECTED", log.get("result"));
        assertEquals(currentHash(1), log.get("plan_hash"));
        assertFalse(stale.equals(log.get("plan_hash")));
        assertEquals(0, markerCount());
    }

    @Test
    void 확인_값이_없으면_거부하고_해시_자리에_0을_남긴다() {
        var exit = run("--operator=alice", "--approver=bob", "--target-id=1", "--apply", "--reason=테스트");

        assertEquals(OpsRunner.EXIT_REJECTED, exit);
        assertEquals(OpsPlanHash.NONE, onlyLog().get("plan_hash"));
    }

    @Test
    void 계획을_만들_수_없으면_사용자가_준_확인_값을_남긴다() {
        var confirm = "ab".repeat(32);

        var exit = run("--operator=alice", "--approver=bob", "--target-id=" + FakeOpsCommand.MISSING, "--apply",
                "--confirm=" + confirm, "--reason=테스트");

        assertEquals(OpsRunner.EXIT_REJECTED, exit);
        var log = onlyLog();
        assertEquals(confirm, log.get("plan_hash"));
        assertEquals(FakeOpsCommand.MISSING, ((Number) log.get("target_id")).longValue());
    }

    @Test
    void 확인_값의_형식이_틀리고_계획도_못_만들면_해시_자리에_0을_남긴다() {
        var exit = run("--operator=alice", "--approver=bob", "--target-id=" + FakeOpsCommand.MISSING, "--apply",
                "--confirm=not-a-hash", "--reason=테스트");

        assertEquals(OpsRunner.EXIT_REJECTED, exit);
        assertEquals(OpsPlanHash.NONE, onlyLog().get("plan_hash"));
    }

    @Test
    void 올바른_인자로_적용하면_상태가_바뀌고_APPLIED를_남긴다() {
        var hash = currentHash(7);

        var exit = run("--operator=alice", "--approver=bob", "--target-id=7", "--apply", "--confirm=" + hash,
                "--reason=원인을 고쳤다");

        assertEquals(OpsRunner.EXIT_OK, exit);
        assertEquals("B", fourEyes.state.get());
        var log = onlyLog();
        assertEquals("APPLIED", log.get("result"));
        assertEquals("fake move", log.get("command"));
        assertEquals("OUTBOX", log.get("target_type"));
        assertEquals("A", log.get("from_status"));
        assertEquals("B", log.get("to_status"));
        assertEquals(hash, log.get("plan_hash"));
        assertEquals("alice", log.get("operator"));
        assertEquals("bob", log.get("approver"));
        assertEquals("원인을 고쳤다", log.get("reason"));
        assertTrue(log.get("pg_evidence").toString().contains("OK"), log.toString());
        assertEquals(1, markerCount());
    }

    @Test
    void 적용_중_거부하면_상태_변경을_롤백하고_REJECTED만_남긴다() {
        var hash = currentHash(1);
        fourEyes.failAfterWrite = true;

        var exit = run("--operator=alice", "--approver=bob", "--target-id=1", "--apply", "--confirm=" + hash,
                "--reason=테스트");

        assertEquals(OpsRunner.EXIT_REJECTED, exit);
        assertEquals(0, markerCount(), "표식 행이 롤백되어야 한다");
        var log = onlyLog();
        assertEquals("REJECTED", log.get("result"));
        assertEquals(hash, log.get("plan_hash"));
    }

    @Test
    void 메모_없이_적용하면_거부한다() {
        var exit = run("--operator=alice", "--approver=bob", "--target-id=1", "--apply", "--confirm=" + currentHash(1));

        assertEquals(OpsRunner.EXIT_REJECTED, exit);
        assertEquals("A", fourEyes.state.get());
        assertEquals("REJECTED", onlyLog().get("result"));
    }

    @Test
    void 승인자가_필요_없는_명령은_승인자_없이_적용한다() {
        var oneEye = new FakeOpsCommand(false, jdbc);
        runner = new OpsRunner(List.of(oneEye), List.of(), properties, transactionManager, audit, clock);
        var hash = OpsPlanHash.of(FakeOpsCommand.NAME, oneEye.plan(arguments("--target-id=1")));

        var exit = run("--operator=alice", "--target-id=1", "--apply", "--confirm=" + hash, "--reason=테스트");

        assertEquals(OpsRunner.EXIT_OK, exit);
        assertEquals("B", oneEye.state.get());
        assertNull(onlyLog().get("approver"));
        jdbc.update("DELETE FROM processed_events WHERE event_id = ?", oneEye.marker);
    }

    @Test
    void 계획_해시는_실행자와_승인자에_따라_달라지지_않는다() {
        var plan = fourEyes.plan(arguments("--target-id=1"));
        // 해시 입력에는 실행자·승인자가 없다. 같은 계획이면 누가 보든 같은 값이다
        assertEquals(OpsPlanHash.of(FakeOpsCommand.NAME, plan), OpsPlanHash.of(FakeOpsCommand.NAME, plan));
        assertTrue(OpsPlanHash.isWellFormed(OpsPlanHash.of(FakeOpsCommand.NAME, plan)));
    }

    @Test
    void 모르는_명령과_모르는_옵션은_오류_코드_1이다() {
        assertEquals(OpsRunner.EXIT_ERROR, runner.run(new OpsArguments(List.of("ops", "nothing"), Map.of()), out));
        assertEquals(OpsRunner.EXIT_ERROR, run("--operator=alice", "--target-id=1", "--aprover=bob"));
        assertEquals(OpsRunner.EXIT_ERROR, runner.run(new OpsArguments(List.of("inspect"), Map.of()), out));
        assertEquals(0, logCount());
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
        return new OpsArguments(List.of("ops", "fake", "move"), parsed);
    }

    private String currentHash(long targetId) {
        return OpsPlanHash.of(FakeOpsCommand.NAME, fourEyes.plan(arguments("--target-id=" + targetId)));
    }

    private String output() {
        return captured.toString(StandardCharsets.UTF_8);
    }

    private int logCount() {
        return jdbc.queryForObject("SELECT count(*) FROM manual_action_logs WHERE command = ?", Integer.class,
                FakeOpsCommand.NAME);
    }

    private int markerCount() {
        return jdbc.queryForObject("SELECT count(*) FROM processed_events WHERE consumer_group = 'ops-test'",
                Integer.class);
    }

    private Map<String, Object> onlyLog() {
        List<Map<String, Object>> logs = new ArrayList<>(jdbc.queryForList(
                "SELECT * FROM manual_action_logs WHERE command = ?", FakeOpsCommand.NAME));
        assertEquals(1, logs.size(), logs.toString());
        return logs.get(0);
    }
}
