package com.iseonjwa.payment.ops;

import com.iseonjwa.payment.ops.application.OpsApplyContext;
import com.iseonjwa.payment.ops.application.OpsArguments;
import com.iseonjwa.payment.ops.application.OpsCommand;
import com.iseonjwa.payment.ops.application.OpsPlan;
import com.iseonjwa.payment.ops.application.OpsRejection;
import com.iseonjwa.payment.ops.application.OpsTarget;
import com.iseonjwa.payment.ops.domain.OpsTargetType;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 실행기 통제를 확인하는 가짜 명령. 상태는 메모리의 문자열 하나({@code A -> B})이고, 적용하면 DB에 표식 행을 하나
 * 쓴다. 표식이 적용 트랜잭션과 함께 커밋되거나 롤백되는지 본다. 대상 404는 계획을 만들 수 없는 경우다.
 */
final class FakeOpsCommand implements OpsCommand {
    static final String NAME = "fake move";
    static final long MISSING = 404;

    final AtomicReference<String> state = new AtomicReference<>("A");
    final UUID marker = UUID.randomUUID();
    private final boolean fourEyes;
    private final JdbcTemplate jdbc;
    /** true면 표식을 쓴 뒤 0행처럼 거부한다 */
    boolean failAfterWrite;

    FakeOpsCommand(boolean fourEyes, JdbcTemplate jdbc) {
        this.fourEyes = fourEyes;
        this.jdbc = jdbc;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public boolean requiresApprover() {
        return fourEyes;
    }

    @Override
    public Set<String> options() {
        return Set.of("target-id");
    }

    @Override
    public OpsTarget target(OpsArguments arguments) {
        return new OpsTarget(OpsTargetType.OUTBOX, arguments.requireLong("target-id"));
    }

    @Override
    public OpsPlan plan(OpsArguments arguments) {
        var target = target(arguments);
        if (target.id() == MISSING) {
            throw new OpsRejection("대상이 없습니다: " + target);
        }
        return new OpsPlan(target, "state=" + state.get(), "{\"status\":\"OK\"}", state.get() + " -> B", state.get(), "B",
                List.of("FakeEvent"), null, List.of());
    }

    @Override
    public OpsPlan apply(OpsPlan plan, OpsApplyContext context) {
        jdbc.update("INSERT INTO processed_events (event_id, consumer_group, processed_at) VALUES (?, 'ops-test', now())",
                marker);
        if (failAfterWrite || !state.get().equals(plan.fromStatus())) {
            throw new OpsRejection("조건부 UPDATE가 0행입니다");
        }
        state.set("B");
        return plan;
    }
}
