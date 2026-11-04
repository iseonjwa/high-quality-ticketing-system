package com.iseonjwa.payment.ops.application;

import com.iseonjwa.common.messaging.outbox.OutboxRepository;
import com.iseonjwa.payment.ops.domain.OpsInspectionRepository;
import com.iseonjwa.payment.ops.domain.OpsTargetType;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * {@code outbox requeue}: 영구 오류로 {@code FAILED}가 된 Outbox 행을 원인을 고친 뒤 {@code PENDING}으로 되돌린다
 * (manual-recovery.md 4절). 이미 정해진 이벤트를 다시 보내는 것이라 1인 명령이다(ADR 0028 결정 8).
 *
 * <p>Note: 이 명령은 이벤트를 직접 발행하지 않는다. Ops JVM은 행의 상태만 되돌리고, 실제 발행은 떠 있는 결제 서비스의
 * 릴레이가 한다(그래서 Ops JVM은 Kafka에 연결하지 않는다). {@code attempts}와 {@code last_error}는 남겨 어떤 오류로
 * 멈췄는지 추적할 수 있게 한다. 결제 상태 전이가 아니므로 상태 이력({@code payment_status_histories})은 쓰지 않는다.
 *
 * <p>Note: 계획 스냅숏에는 행 자체의 값만 넣고 같은 키의 뒤 행 수는 넣지 않는다. 뒤 행 수는 새 이벤트가 쌓이면 달라지는
 * 값이라 해시에 넣으면 이 행에 아무 일이 없어도 확인 값이 어긋난다.
 */
@Component
public class OutboxRequeueCommand implements OpsCommand {
    public static final String NAME = "outbox requeue";

    private final OpsInspectionRepository inspection;
    private final OutboxRepository outbox;

    public OutboxRequeueCommand(OpsInspectionRepository inspection, OutboxRepository outbox) {
        this.inspection = inspection;
        this.outbox = outbox;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public boolean requiresApprover() {
        return false;
    }

    @Override
    public Set<String> options() {
        return Set.of("outbox-id");
    }

    @Override
    public OpsTarget target(OpsArguments arguments) {
        return new OpsTarget(OpsTargetType.OUTBOX, arguments.requireLong("outbox-id"));
    }

    @Override
    public OpsPlan plan(OpsArguments arguments) {
        var target = target(arguments);
        var row = inspection.findOutbox(target.id())
                .orElseThrow(() -> new OpsRejection("Outbox 행이 없습니다: " + target.id()));
        if (!row.status().equals("FAILED")) {
            throw new OpsRejection("FAILED인 행만 되살립니다. 현재 상태는 " + row.status() + "입니다");
        }
        var backlog = inspection.findOutboxBacklog(row);
        var details = new ArrayList<String>();
        details.add("이벤트: " + row.eventType() + " 토픽=" + row.topic() + " 키=" + row.messageKey());
        details.add("마지막 오류: " + row.lastError());
        details.add("같은 키에서 이 행 뒤에 막혀 있는 PENDING: " + backlog.pendingBehind() + "건");
        if (backlog.failedBehind() > 0) {
            details.add("같은 키에서 뒤에 있는 다른 FAILED: " + backlog.failedBehind() + "건(각각 따로 되살려야 한다)");
        }
        if (backlog.failedAhead() > 0) {
            details.add("주의: 같은 키에서 앞에 있는 다른 FAILED가 " + backlog.failedAhead()
                    + "건 있다. 그 행을 먼저 되살려야 이 행이 나간다(키별 순서)");
        }
        var snapshot = "status=" + row.status() + " attempts=" + row.attempts() + " eventId=" + row.eventId()
                + " eventType=" + row.eventType() + " topic=" + row.topic() + " key=" + row.messageKey()
                + " lastError=" + row.lastError();
        return new OpsPlan(target, snapshot, null,
                "FAILED -> PENDING (attempts 유지, next_attempt_at=실행 시각, claimed_until=NULL, last_error 유지)",
                "FAILED", "PENDING", List.of(row.eventType() + " 재발행(릴레이가 같은 eventId로 발행)"), null, details);
    }

    @Override
    public OpsPlan apply(OpsPlan plan, OpsApplyContext context) {
        if (!outbox.requeueFailed(plan.target().id(), context.now())) {
            throw new OpsRejection("조건부 UPDATE가 0행입니다. 그 사이 FAILED가 아니게 되었습니다");
        }
        return plan;
    }
}
