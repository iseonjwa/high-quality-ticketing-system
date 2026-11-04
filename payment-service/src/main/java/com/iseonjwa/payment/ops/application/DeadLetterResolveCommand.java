package com.iseonjwa.payment.ops.application;

import com.iseonjwa.payment.ops.domain.OpsInspectionRepository;
import com.iseonjwa.payment.ops.domain.OpsTargetType;
import com.iseonjwa.payment.payment.domain.PaymentStatus;
import com.iseonjwa.payment.refund.application.DeadLetterReplayService;
import com.iseonjwa.payment.refund.application.DeadLetterReplayService.ReplayCheck;
import com.iseonjwa.payment.refund.application.DeadLetterReplayService.ReplayResult;
import com.iseonjwa.payment.refund.domain.DeadLetter;
import com.iseonjwa.payment.refund.domain.DeadLetterRepository;
import com.iseonjwa.payment.refund.domain.DeadLetterResolution;
import com.iseonjwa.payment.refund.domain.DeadLetterStatus;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * {@code dead-letter resolve}: {@code OPEN}인 {@code dead_letters}를 닫는다(manual-recovery.md 3절). 4-eyes 명령이고
 * {@code --replay}와 {@code --no-obligation} 중 정확히 하나를 지정한다.
 *
 * <p>{@code --replay}는 원문을 DLQ 처리기와 같은 파서·검증·생성 경로로 다시 처리한다
 * ({@link DeadLetterReplayService}). 환불을 만들면 {@code RESOLVED(REPLAYED)}, 환불이 이미 있으면
 * {@code RESOLVED(DUPLICATE)}다. 읽지 못하거나 검증에 실패하거나 결제가 {@code APPROVED}가 아니면 거부하고 {@code OPEN}으로
 * 둔다. 계획 단계는 검증까지만 하고 쓰지 않는다.
 *
 * <p>{@code --no-obligation}은 환불 의무가 없음을 운영자가 확인하고 닫는다. 환불을 만들지 않으며
 * {@code RESOLVED(NO_OBLIGATION)}이 된다. 근거는 {@code --reason}(메모)이다.
 *
 * <p>Note: 감사 기록의 {@code to_status}에는 닫힌 방식({@code REPLAYED}·{@code DUPLICATE}·{@code NO_OBLIGATION})을 쓴다.
 * 상태는 모두 {@code RESOLVED}라 정보가 없고, 컬럼이 20자라 {@code RESOLVED(NO_OBLIGATION)}은 들어가지 않는다.
 *
 * <p>Note: 적용에서 {@code OPEN} 여부를 따로 읽어 확인하지 않는다. 닫는 조건부 UPDATE({@code status = 'OPEN'})가 0행이면
 * 거부하고 트랜잭션을 롤백해, 이미 만든 환불까지 함께 되돌린다. 계획을 만든 뒤 다른 경로가 먼저 닫은 경우를 같은 길로 막는다.
 */
@Component
public class DeadLetterResolveCommand implements OpsCommand {
    public static final String NAME = "dead-letter resolve";
    private static final String OPEN = DeadLetterStatus.OPEN.name();
    private static final String APPROVED = PaymentStatus.APPROVED.name();
    private static final String REPLAY_HINT = ". 데이터를 고치거나 환불 의무가 없으면 --no-obligation을 쓴다";

    private final DeadLetterRepository deadLetters;
    private final OpsInspectionRepository inspection;
    private final DeadLetterReplayService replay;

    public DeadLetterResolveCommand(DeadLetterRepository deadLetters, OpsInspectionRepository inspection,
            DeadLetterReplayService replay) {
        this.deadLetters = deadLetters;
        this.inspection = inspection;
        this.replay = replay;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public boolean requiresApprover() {
        return true;
    }

    @Override
    public Set<String> options() {
        return Set.of("dead-letter-id", "replay", "no-obligation");
    }

    @Override
    public OpsTarget target(OpsArguments arguments) {
        // 모드가 잘못되면 대상을 정하는 단계에서 사용법 오류로 끝낸다. 상태를 읽지도 쓰지도 않는다
        mode(arguments);
        return new OpsTarget(OpsTargetType.DEAD_LETTER, arguments.requireLong("dead-letter-id"));
    }

    @Override
    public OpsPlan plan(OpsArguments arguments) {
        var target = target(arguments);
        var deadLetter = loadOpen(target.id());
        return switch (mode(arguments)) {
            case REPLAY -> planReplay(target, deadLetter);
            case NO_OBLIGATION -> planNoObligation(target, deadLetter);
        };
    }

    @Override
    public OpsPlan apply(OpsPlan plan, OpsApplyContext context) {
        var deadLetter = deadLetters.findById(plan.target().id())
                .orElseThrow(() -> new OpsRejection("dead_letters가 없습니다: " + plan.target().id()));
        if (plan.toStatus().equals(DeadLetterResolution.NO_OBLIGATION.name())) {
            if (!deadLetters.resolve(deadLetter.id(), DeadLetterResolution.NO_OBLIGATION, null, context.now())) {
                throw notOpen();
            }
            return plan;
        }
        return switch (replay.replay(deadLetter, context.actor())) {
            case ReplayResult.Rejected rejected -> throw new OpsRejection(rejected.message() + REPLAY_HINT);
            case ReplayResult.NotOpen notOpen -> throw notOpen();
            case ReplayResult.Closed closed -> appliedPlan(plan, closed);
        };
    }

    private DeadLetter loadOpen(long deadLetterId) {
        var deadLetter = deadLetters.findById(deadLetterId)
                .orElseThrow(() -> new OpsRejection("dead_letters가 없습니다: " + deadLetterId));
        if (deadLetter.status() != DeadLetterStatus.OPEN) {
            throw new OpsRejection(OPEN + "인 dead_letters만 처리합니다. 현재 상태는 " + deadLetter.status() + "("
                    + deadLetter.resolution() + ")입니다");
        }
        return deadLetter;
    }

    private static Mode mode(OpsArguments arguments) {
        var replay = arguments.flag("replay");
        var noObligation = arguments.flag("no-obligation");
        if (replay == noObligation) {
            throw new OpsUsageException("--replay와 --no-obligation 중 정확히 하나를 지정해야 합니다");
        }
        return replay ? Mode.REPLAY : Mode.NO_OBLIGATION;
    }

    private OpsPlan planReplay(OpsTarget target, DeadLetter deadLetter) {
        var check = replay.check(deadLetter.payloadRaw());
        if (!(check instanceof ReplayCheck.Verified verified)) {
            throw new OpsRejection(((ReplayCheck.Unreplayable) check).message() + REPLAY_HINT);
        }
        var payload = verified.payload();
        var existing = inspection.findRefundByPaymentId(payload.paymentId());
        var paymentStatus = inspection.findPayment(payload.paymentId()).orElseThrow().status();
        if (existing.isEmpty() && !paymentStatus.equals(APPROVED)) {
            throw new OpsRejection("환불을 시작할 수 없는 결제 상태입니다: paymentId=" + payload.paymentId() + ", status="
                    + paymentStatus + REPLAY_HINT);
        }
        var snapshot = snapshot(deadLetter, "replay") + " paymentId=" + payload.paymentId() + " reservationId="
                + payload.reservationId() + " eventReason=" + verified.reason() + " paymentStatus=" + paymentStatus
                + " refund=" + (existing.isPresent() ? existing.get().id() + "(" + existing.get().status() + ")" : "none");
        var details = new java.util.ArrayList<String>();
        details.add("원문을 DLQ 처리기와 같은 파서·검증으로 읽었고 결제와 맞는다(eventId=" + verified.eventId() + ")");
        String transition;
        String toStatus;
        if (existing.isPresent()) {
            toStatus = DeadLetterResolution.DUPLICATE.name();
            transition = "dead_letters OPEN -> RESOLVED(DUPLICATE), 결제의 환불이 이미 있어 만들지 않는다(refund_id=" + existing.get().id() + ")";
        } else {
            toStatus = DeadLetterResolution.REPLAYED.name();
            transition = "dead_letters OPEN -> RESOLVED(REPLAYED), 환불 REQUESTED 생성(source=DLQ, 결제 " + payload.paymentId() + ")";
            details.add("환불 워커가 다음 주기에 이어받는다. 이 명령은 PG를 부르지 않는다");
        }
        return new OpsPlan(target, snapshot, null, transition, OPEN, toStatus, List.of(), null, details);
    }

    private OpsPlan planNoObligation(OpsTarget target, DeadLetter deadLetter) {
        var details = new java.util.ArrayList<String>();
        details.add("사유: " + deadLetter.reason());
        details.add("환불 의무가 없음을 확인했다는 기록이 --reason(메모)이다. 환불은 만들지 않고 PG도 부르지 않는다");
        details.add("실제 의무가 남아 있었다면 대사 RC-2가 다시 찾는다");
        return new OpsPlan(target, snapshot(deadLetter, "no-obligation"), null,
                "dead_letters OPEN -> RESOLVED(NO_OBLIGATION), 환불을 만들지 않는다", OPEN,
                DeadLetterResolution.NO_OBLIGATION.name(), List.of(), null, details);
    }

    private static String snapshot(DeadLetter deadLetter, String mode) {
        return "deadLetterStatus=" + deadLetter.status() + " eventKey=" + deadLetter.eventKey() + " mode=" + mode;
    }

    // 계획을 만든 뒤 환불이 생겼거나 사라졌어도 실제로 일어난 결과를 감사와 출력에 남긴다
    private static OpsPlan appliedPlan(OpsPlan plan, ReplayResult.Closed closed) {
        var transition = closed.resolution() == DeadLetterResolution.REPLAYED
                ? "dead_letters OPEN -> RESOLVED(REPLAYED), 환불 REQUESTED 생성(refund_id=" + closed.refundId() + ")"
                : "dead_letters OPEN -> RESOLVED(DUPLICATE), 환불은 이미 있어 만들지 않았다(refund_id=" + closed.refundId() + ")";
        return new OpsPlan(plan.target(), plan.snapshot(), plan.pgEvidence(), transition, plan.fromStatus(),
                closed.resolution().name(), plan.events(), plan.externalRef(), plan.details());
    }

    private static OpsRejection notOpen() {
        return new OpsRejection("조건부 UPDATE가 0행입니다. 그 사이 dead_letters가 " + OPEN + "가 아니게 되었습니다");
    }

    private enum Mode { REPLAY, NO_OBLIGATION }
}
