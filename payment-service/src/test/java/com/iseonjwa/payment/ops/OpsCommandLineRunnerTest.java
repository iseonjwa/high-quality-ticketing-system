package com.iseonjwa.payment.ops;

import com.iseonjwa.payment.ops.application.OpsArguments;
import com.iseonjwa.payment.ops.application.OpsProperties;
import com.iseonjwa.payment.ops.application.OpsQuery;
import com.iseonjwa.payment.ops.application.OpsRunner;
import com.iseonjwa.payment.ops.domain.ManualActionLogRepository;
import com.iseonjwa.payment.ops.presentation.OpsCommandLineRunner;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Clock;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

/** 기동 인자에서 Ops 옵션만 뽑는지 확인한다. Spring 설정 인자(--spring.*, --payment.*)가 섞여도 사용법 오류가 되면 안 된다. */
class OpsCommandLineRunnerTest {
    @Test
    void Spring_설정_인자는_Ops_옵션으로_보지_않는다() {
        var query = new OpsQuery() {
            OpsArguments received;

            @Override
            public String name() {
                return "record";
            }

            @Override
            public Set<String> options() {
                return Set.of("payment-id");
            }

            @Override
            public List<String> report(OpsArguments arguments) {
                received = arguments;
                return List.of("ok");
            }
        };
        var runner = new OpsRunner(List.of(), List.of(query), new OpsProperties(List.of("alice")),
                mock(PlatformTransactionManager.class), mock(ManualActionLogRepository.class), Clock.systemUTC());
        var commandLine = new OpsCommandLineRunner(runner);

        commandLine.run(new DefaultApplicationArguments("ops", "record", "--operator=alice", "--payment-id=4",
                "--spring.profiles.active=local,ops", "--payment.ops.operators=alice"));

        assertEquals(OpsRunner.EXIT_OK, commandLine.getExitCode());
        assertEquals("4", query.received.value("payment-id"));
    }

    @Test
    void 명령이_실행되기_전에는_오류_코드다() {
        var runner = new OpsRunner(List.of(), List.of(), new OpsProperties(List.of()),
                mock(PlatformTransactionManager.class), mock(ManualActionLogRepository.class), Clock.systemUTC());

        assertEquals(OpsRunner.EXIT_ERROR, new OpsCommandLineRunner(runner).getExitCode());
    }
}
