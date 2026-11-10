package com.iseonjwa.payment.scenario;

import com.iseonjwa.payment.ops.application.OpsArguments;
import com.iseonjwa.payment.ops.application.OpsPgLookup;
import com.iseonjwa.payment.ops.application.OpsProperties;
import com.iseonjwa.payment.ops.application.OpsRunner;
import com.iseonjwa.payment.ops.application.PaymentResolveCommand;
import com.iseonjwa.payment.ops.application.RefundResolveCommand;
import com.iseonjwa.payment.ops.application.RefundRetryCommand;
import com.iseonjwa.payment.ops.domain.ManualActionLogRepository;
import com.iseonjwa.payment.ops.domain.OpsInspectionRepository;
import com.iseonjwa.payment.payment.application.PaymentTransitionService;
import com.iseonjwa.payment.pg.application.PgClients;
import com.iseonjwa.payment.refund.application.RefundRequestService;
import com.iseonjwa.payment.refund.application.RefundRetryService;
import com.iseonjwa.payment.refund.application.RefundSettlementService;
import org.springframework.context.ApplicationContext;
import org.springframework.transaction.PlatformTransactionManager;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 시나리오 테스트가 Ops 명령(ADR 0028)을 운영자처럼 부르는 도우미. 명령 객체는 운영과 같은 빈으로 만들고, {@code ops} 프로필의
 * 명령줄 진입점은 거치지 않고 {@link OpsRunner}를 직접 부른다. 실행자는 {@code alice}, 승인자는 {@code bob}이다(테스트 허용 목록).
 *
 * <p>Note: 적용은 운영 절차대로 dry-run이 출력한 계획 해시를 {@code --confirm}으로 되받아 보낸다. 해시를 테스트가 계산하지 않고
 * 출력에서 읽어서, 사람이 하는 두 단계 절차를 그대로 거친다.
 */
final class ScenarioOps {
    static final String OPERATOR = "alice";
    static final String APPROVER = "bob";
    private static final Pattern PLAN_HASH = Pattern.compile("계획 해시: ([0-9a-f]{64})");

    private final OpsRunner runner;

    ScenarioOps(ApplicationContext context) {
        var inspection = context.getBean(OpsInspectionRepository.class);
        var lookup = new OpsPgLookup(context.getBeanProvider(PgClients.class));
        var commands = List.of(
                new RefundRetryCommand(inspection, lookup, context.getBean(RefundRetryService.class)),
                new RefundResolveCommand(inspection, lookup, context.getBean(RefundSettlementService.class)),
                new PaymentResolveCommand(inspection, lookup, context.getBean(PaymentTransitionService.class),
                        context.getBean(RefundRequestService.class)));
        runner = new OpsRunner(commands, List.of(), context.getBean(OpsProperties.class),
                context.getBean(PlatformTransactionManager.class), context.getBean(ManualActionLogRepository.class),
                context.getBean("commonAuditingClock", Clock.class));
    }

    /** dry-run. 상태를 바꾸지 않고 계획과 해시를 출력한다. */
    Result dryRun(String command, String... options) {
        return run(command, options, "--operator=" + OPERATOR);
    }

    /** 이미 아는 확인 값으로 적용을 시도한다. 해시가 맞지 않거나 근거가 어긋나면 거부된다. */
    Result applyWithConfirm(String command, String confirm, String reason, String... options) {
        return run(command, options, "--operator=" + OPERATOR, "--approver=" + APPROVER, "--apply", "--confirm=" + confirm,
                "--reason=" + reason);
    }

    /** 운영 절차: dry-run으로 계획 해시를 받고 같은 인자에 해시를 붙여 적용한다. dry-run이 거부되면 적용하지 않고 그 결과를 돌려준다. */
    Result dryRunThenApply(String command, String reason, String... options) {
        var plan = dryRun(command, options);
        if (plan.exit() != OpsRunner.EXIT_OK) return plan;
        return applyWithConfirm(command, plan.planHash(), reason, options);
    }

    private Result run(String command, String[] options, String... common) {
        var parsed = new LinkedHashMap<String, List<String>>();
        for (var option : concat(options, common)) {
            var body = option.substring(2);
            var separator = body.indexOf('=');
            if (separator < 0) {
                parsed.put(body, List.of());
            } else {
                parsed.put(body.substring(0, separator), List.of(body.substring(separator + 1)));
            }
        }
        var words = new java.util.ArrayList<String>();
        words.add("ops");
        words.addAll(List.of(command.split(" ")));
        var captured = new ByteArrayOutputStream();
        var exit = runner.run(new OpsArguments(words, parsed), new PrintStream(captured, true, StandardCharsets.UTF_8));
        return new Result(exit, captured.toString(StandardCharsets.UTF_8));
    }

    private static String[] concat(String[] first, String[] second) {
        var all = new String[first.length + second.length];
        System.arraycopy(first, 0, all, 0, first.length);
        System.arraycopy(second, 0, all, first.length, second.length);
        return all;
    }

    record Result(int exit, String output) {
        String planHash() {
            var matcher = PLAN_HASH.matcher(output);
            if (!matcher.find()) throw new AssertionError("출력에 계획 해시가 없다:\n" + output);
            return matcher.group(1);
        }
    }
}
