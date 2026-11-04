package com.iseonjwa.payment.ops.application;

import com.iseonjwa.payment.ops.domain.ManualActionLog;
import com.iseonjwa.payment.ops.domain.ManualActionLogRepository;
import com.iseonjwa.payment.ops.domain.ManualActionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.PrintStream;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Ops 명령 실행기(ADR 0028). 인자 해석, 실행자·승인자 통제, dry-run, 계획 해시 비교, 적용 트랜잭션, 감사 기록, 종료
 * 코드를 맡는다. 명령({@link OpsCommand})은 계획 만들기와 적용하기만 선언한다.
 *
 * <p>종료 코드: 0 = 성공(dry-run 계획 출력 또는 적용), 2 = 거부(통제 위반·근거 불일치·해시 불일치·0행), 1 = 오류(사용법
 * 오류 포함). 결과는 표준 출력에 사람이 읽는 평문으로 쓴다.
 *
 * <p>Note: 운영자 ID는 {@code --operator}로 받는 자기 신고이고 암호학적으로 증명되지 않는다. 감사 기록의 정확성은
 * 호스트의 SSH 접근 통제(팀원 키 인증)에 기댄다. 허용 목록은 오타와 외부인을 막을 뿐 신원을 보증하지 않는다.
 *
 * <p>Note: 감사 기록은 {@code --apply} 시도에서 생긴 결과만 남긴다. APPLIED는 상태 변경과 같은 트랜잭션에 써서 둘이
 * 함께 커밋되거나 롤백되고, REJECTED는 적용 트랜잭션이 롤백된 뒤 별도 트랜잭션으로 쓴다. dry-run과 inspect는 상태를
 * 바꾸지 않으므로 남기지 않는다. 실행자를 알 수 없으면(--operator 없음) 누구의 시도인지 기록할 수 없어 남기지 않는다.
 *
 * <p>Note: 통제 위반(허용 목록 밖, 승인자 없음·같음, 메모·확인 값 없음)은 계획을 만들기 전에 거부한다. 자격이 없는
 * 요청이 PG 조회를 일으키지 않게 하려는 것이다. 그래서 이 거부 기록의 계획 해시는 다시 만든 계획이 아니라 사용자가 준
 * 확인 값(형식이 틀리면 64자 0)이다.
 */
@Service
public class OpsRunner {
    public static final int EXIT_OK = 0;
    public static final int EXIT_ERROR = 1;
    public static final int EXIT_REJECTED = 2;

    private static final Logger log = LoggerFactory.getLogger(OpsRunner.class);
    private static final Set<String> COMMON_OPTIONS = Set.of("operator", "approver", "apply", "confirm", "reason");
    private static final int ID_MAX_LENGTH = 40;
    private static final int REASON_MAX_LENGTH = 500;

    private final Map<String, OpsCommand> commands = new LinkedHashMap<>();
    private final Map<String, OpsQuery> queries = new LinkedHashMap<>();
    private final OpsProperties properties;
    private final TransactionTemplate transaction;
    private final TransactionTemplate separateTransaction;
    private final ManualActionLogRepository audit;
    private final Clock clock;

    public OpsRunner(List<OpsCommand> commands, List<OpsQuery> queries, OpsProperties properties,
            PlatformTransactionManager transactionManager, ManualActionLogRepository audit,
            @Qualifier("commonAuditingClock") Clock clock) {
        commands.forEach(command -> register(command.name(), command, this.commands));
        queries.forEach(query -> register(query.name(), query, this.queries));
        this.properties = properties;
        this.transaction = new TransactionTemplate(transactionManager);
        this.separateTransaction = new TransactionTemplate(transactionManager);
        this.separateTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.audit = audit;
        this.clock = clock;
    }

    private <T> void register(String name, T command, Map<String, T> into) {
        if (commands.containsKey(name) || queries.containsKey(name)) {
            throw new IllegalStateException("Ops 명령 이름이 겹칩니다: " + name);
        }
        into.put(name, command);
    }

    /** 명령 하나를 실행하고 종료 코드를 돌려준다. 결과는 {@code out}에 쓴다. */
    public int run(OpsArguments arguments, PrintStream out) {
        try {
            return dispatch(arguments, out);
        } catch (OpsUsageException exception) {
            out.println("사용법 오류: " + exception.getMessage());
            out.println("사용법: ops <명령> [--옵션=값 ...]. 명령: " + String.join(", ", new TreeSet<>(names())));
            return EXIT_ERROR;
        } catch (OpsRejection exception) {
            // 감사 대상이 아닌 거부: dry-run, inspect, 실행자를 알 수 없는 시도
            out.println("거부: " + exception.getMessage());
            return EXIT_REJECTED;
        } catch (RuntimeException exception) {
            log.error("Ops 명령 오류", exception);
            out.println("오류: " + exception.getMessage());
            return EXIT_ERROR;
        }
    }

    private Set<String> names() {
        var names = new TreeSet<>(commands.keySet());
        names.addAll(queries.keySet());
        return names;
    }

    private int dispatch(OpsArguments arguments, PrintStream out) {
        var name = arguments.commandName();
        var query = queries.get(name);
        if (query != null) {
            return inspect(query, arguments, out);
        }
        var command = commands.get(name);
        if (command == null) {
            throw new OpsUsageException("알 수 없는 명령입니다: " + name);
        }
        arguments.requireOnly(allowedOptions(command.options()));
        out.println("== ops " + name + " ==");
        return arguments.flag("apply") ? apply(command, arguments, out) : dryRun(command, arguments, out);
    }

    private int inspect(OpsQuery query, OpsArguments arguments, PrintStream out) {
        arguments.requireOnly(withOperator(query.options()));
        out.println("== ops " + query.name() + " ==");
        requireAllowedOperator(arguments.value("operator"));
        query.report(arguments).forEach(out::println);
        return EXIT_OK;
    }

    private int dryRun(OpsCommand command, OpsArguments arguments, PrintStream out) {
        requireAllowedOperator(arguments.value("operator"));
        command.target(arguments);
        var plan = command.plan(arguments);
        var planHash = OpsPlanHash.of(command.name(), plan);
        out.println("[dry-run] 상태를 바꾸지 않았다");
        out.println("대상: " + plan.target());
        out.println("현재 상태: " + plan.snapshot());
        if (plan.pgEvidence() != null) {
            out.println("PG 근거: " + plan.pgEvidence());
        }
        out.println("바뀔 전이: " + plan.transition());
        out.println("발행할 이벤트: " + (plan.events().isEmpty() ? "없음" : String.join(", ", plan.events())));
        plan.details().forEach(out::println);
        out.println("계획 해시: " + planHash);
        out.println("적용하려면 같은 인자에 --apply --confirm=" + planHash + " --reason=<메모>"
                + (command.requiresApprover() ? " --approver=<승인자>" : "") + "를 붙인다");
        return EXIT_OK;
    }

    private int apply(OpsCommand command, OpsArguments arguments, PrintStream out) {
        var operator = arguments.value("operator");
        if (operator == null) {
            throw new OpsRejection("--operator가 필요합니다");
        }
        var attempt = new Attempt(command.name(), command.target(arguments), operator, arguments.value("approver"),
                arguments.value("reason"), arguments.value("confirm"));
        var violation = controlViolation(command, attempt);
        if (violation != null) {
            return reject(attempt, violation, fallbackHash(attempt.confirm), out);
        }

        OpsPlan plan;
        try {
            plan = command.plan(arguments);
        } catch (OpsRejection rejection) {
            return reject(attempt, rejection.getMessage(), fallbackHash(attempt.confirm), out);
        }
        var planHash = OpsPlanHash.of(command.name(), plan);
        if (!planHash.equals(attempt.confirm)) {
            return reject(attempt, "계획 해시가 확인 값과 다릅니다. 상태나 근거가 바뀌었을 수 있으니 dry-run을 다시 실행한다",
                    planHash, out);
        }

        var context = new OpsApplyContext(attempt.operator, attempt.approver, clock.instant());
        OpsPlan applied;
        try {
            applied = transaction.execute(status -> {
                var done = command.apply(plan, context);
                audit.insert(new ManualActionLog(command.name(), done.target().type(), done.target().id(),
                        done.fromStatus(), done.toStatus(), ManualActionResult.APPLIED, planHash, done.pgEvidence(),
                        done.externalRef(), truncate(attempt.operator, ID_MAX_LENGTH),
                        attempt.approver == null ? null : truncate(attempt.approver, ID_MAX_LENGTH),
                        truncate(attempt.memo, REASON_MAX_LENGTH), context.now()));
                return done;
            });
        } catch (OpsRejection rejection) {
            return reject(attempt, rejection.getMessage(), planHash, out);
        }
        out.println("적용했다: " + applied.target());
        out.println("전이: " + applied.transition());
        out.println("발행한 이벤트: " + (applied.events().isEmpty() ? "없음" : String.join(", ", applied.events())));
        out.println("계획 해시: " + planHash);
        out.println("감사 기록: APPLIED (실행자 " + attempt.operator
                + (attempt.approver == null ? "" : ", 승인자 " + attempt.approver) + ")");
        return EXIT_OK;
    }

    // 첫 위반 하나만 돌려준다. 위반이 없으면 null이다
    private String controlViolation(OpsCommand command, Attempt attempt) {
        if (!properties.isAllowed(attempt.operator)) {
            return "허용 목록에 없는 실행자입니다: " + attempt.operator;
        }
        if (command.requiresApprover() && attempt.approver == null) {
            return "이 명령은 4-eyes라 --approver가 필요합니다";
        }
        if (attempt.approver != null) {
            if (attempt.approver.equals(attempt.operator)) {
                return "승인자가 실행자와 같습니다: " + attempt.approver;
            }
            if (!properties.isAllowed(attempt.approver)) {
                return "허용 목록에 없는 승인자입니다: " + attempt.approver;
            }
        }
        if (attempt.memo == null) {
            return "--apply에는 감사 기록에 남길 --reason(메모)이 필요합니다";
        }
        if (attempt.confirm == null) {
            return "--apply에는 dry-run이 출력한 --confirm=<계획 해시>가 필요합니다";
        }
        return null;
    }

    private void requireAllowedOperator(String operator) {
        if (operator == null) {
            throw new OpsRejection("--operator가 필요합니다");
        }
        if (!properties.isAllowed(operator)) {
            throw new OpsRejection("허용 목록에 없는 실행자입니다: " + operator);
        }
    }

    private int reject(Attempt attempt, String message, String planHash, PrintStream out) {
        // 승인자가 실행자와 같으면 DB 제약이 거부하므로 거부 기록에는 넣지 않고 사유에 적는다
        var approver = attempt.approver == null ? null : truncate(attempt.approver, ID_MAX_LENGTH);
        if (approver != null && approver.equals(truncate(attempt.operator, ID_MAX_LENGTH))) {
            approver = null;
        }
        var reason = truncate("거부: " + message + (attempt.memo == null ? "" : " / 메모: " + attempt.memo),
                REASON_MAX_LENGTH);
        var log = new ManualActionLog(attempt.command, attempt.target.type(), attempt.target.id(), null, null,
                ManualActionResult.REJECTED, planHash, null, null, truncate(attempt.operator, ID_MAX_LENGTH), approver,
                reason, clock.instant());
        separateTransaction.executeWithoutResult(status -> audit.insert(log));
        out.println("거부: " + message);
        out.println("감사 기록: REJECTED");
        return EXIT_REJECTED;
    }

    private static String fallbackHash(String confirm) {
        return OpsPlanHash.isWellFormed(confirm) ? confirm : OpsPlanHash.NONE;
    }

    private static String truncate(String value, int maxLength) {
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    private static Set<String> withOperator(Set<String> options) {
        var all = new TreeSet<>(options);
        all.add("operator");
        return all;
    }

    private static Set<String> allowedOptions(Set<String> options) {
        var all = new TreeSet<>(COMMON_OPTIONS);
        all.addAll(options);
        return all;
    }

    // 이번 --apply 시도의 인자. 거부 기록에 그대로 쓴다
    private record Attempt(String command, OpsTarget target, String operator, String approver, String memo,
            String confirm) {
    }
}
