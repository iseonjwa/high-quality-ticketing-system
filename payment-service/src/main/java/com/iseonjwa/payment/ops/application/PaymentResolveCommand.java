package com.iseonjwa.payment.ops.application;

import com.iseonjwa.payment.ops.domain.OpsInspectionRepository;
import com.iseonjwa.payment.ops.domain.OpsInspectionRepository.PaymentView;
import com.iseonjwa.payment.ops.domain.OpsTargetType;
import com.iseonjwa.payment.payment.application.PaymentTransitionService;
import com.iseonjwa.payment.payment.domain.DeclineReason;
import com.iseonjwa.payment.payment.domain.PaymentStatus;
import com.iseonjwa.payment.pg.application.PgClient;
import com.iseonjwa.payment.pg.application.PgOrderId;
import com.iseonjwa.payment.refund.application.RefundCreation;
import com.iseonjwa.payment.refund.application.RefundRequestService;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * {@code payment resolve}: {@code NEEDS_MANUAL_CHECK} 결제를 PG 근거로 닫는다(manual-recovery.md 2절). 운영자는 목표
 * 상태를 고르지 않는다. 계획을 만들 때 PG를 다시 조회하고 그 결과가 전이를 정한다(ADR 0028 결정 3). 4-eyes 명령이다.
 *
 * <p>PG 결과별 전이: 승인이고 금액·통화가 같으면 {@code APPROVED}, 승인인데 다르면 환불 {@code REQUESTED}를 만들고 결제는
 * 그대로 둔다(환불 워커가 끝내면 결제가 {@code REFUNDED}), 거절이면 {@code DECLINED}, 못 찾으면 적용 때 늦은 승인 방지를
 * 시도한다, 결과를 모르면 거부한다. 전이는 승인 API·재확인·환불 워커가 쓰는 {@link PaymentTransitionService}와
 * {@link RefundRequestService}를 그대로 부른다(조건부 UPDATE + 이력 + Outbox).
 *
 * <p>Note: PG 결과를 전이로 바꾸는 판정은 {@code PaymentRecheckService}와 닮았지만 공유하지 않았다. 재확인은 금액이 다르면
 * 결제를 {@code NEEDS_MANUAL_CHECK}로 보내고 지표·알림 로그를 남기며 PG 허가 게이트와 임대에 묶여 있다. 여기서는 같은
 * 경우에 환불을 시작한다. 공유하려면 재확인의 동작을 바꿔야 해서 판정을 이 클래스에 두었다.
 *
 * <p>Note: {@code NotFound}의 dry-run은 {@code preventLateApproval}을 부르지 않는다. 이 호출은 PG에 "이 주문은 더 승인하지
 * 않는다"는 흔적을 남겨 되돌릴 수 없다. 적용 때 다시 조회해 여전히 {@code NotFound}일 때만 부른다. 이 호출은 DB 트랜잭션
 * 안에서 한 번 일어나며 연결을 잠깐 쥐지만, 명령 하나당 한 번이고 별도 JVM이라 다른 요청을 막지 않는다. 방지가 이미
 * 승인되었음을 알리면({@code AlreadyApproved}) 승인 줄과 같은 금액 대조로 이어지고, 이때는 적용해 봐야 전이가 정해지므로
 * 실제 전이와 근거를 담은 계획을 돌려준다.
 *
 * <p>Note: 적용 때 PG를 한 번 더 조회해 계획의 근거와 같은지 확인한다. 계획에는 승인 시각처럼 해시에 넣지 않는 값이 없어,
 * 이벤트에 쓸 PG 승인 시각을 이 조회에서 얻는다.
 */
@Component
public class PaymentResolveCommand implements OpsCommand {
    public static final String NAME = "payment resolve";
    private static final String NMC = PaymentStatus.NEEDS_MANUAL_CHECK.name();

    private final OpsInspectionRepository inspection;
    private final OpsPgLookup pg;
    private final PaymentTransitionService transitions;
    private final RefundRequestService refunds;

    public PaymentResolveCommand(OpsInspectionRepository inspection, OpsPgLookup pg,
            PaymentTransitionService transitions, RefundRequestService refunds) {
        this.inspection = inspection;
        this.pg = pg;
        this.transitions = transitions;
        this.refunds = refunds;
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
        return Set.of("payment-id");
    }

    @Override
    public OpsTarget target(OpsArguments arguments) {
        return new OpsTarget(OpsTargetType.PAYMENT, arguments.requireLong("payment-id"));
    }

    @Override
    public OpsPlan plan(OpsArguments arguments) {
        var target = target(arguments);
        var payment = loadNeedsManualCheck(target.id());
        var lookup = pg.findPayment(payment.pgProvider(), new PgOrderId(payment.pgOrderId()));
        return planOf(target, payment, PgEvidence.of(lookup), decide(payment, lookup));
    }

    @Override
    public OpsPlan apply(OpsPlan plan, OpsApplyContext context) {
        var target = plan.target();
        var payment = loadNeedsManualCheck(target.id());
        var lookup = pg.findPayment(payment.pgProvider(), new PgOrderId(payment.pgOrderId()));
        if (!PgEvidence.of(lookup).equals(plan.pgEvidence())) {
            throw new OpsRejection("PG 근거가 계획을 만든 뒤 바뀌었습니다. dry-run을 다시 실행한다");
        }
        var decision = decide(payment, lookup);
        if (decision instanceof Decision.PreventLate) {
            return preventLateApproval(target, payment, context);
        }
        execute(payment, decision, context);
        return plan;
    }

    private PaymentView loadNeedsManualCheck(long paymentId) {
        var payment = inspection.findPayment(paymentId)
                .orElseThrow(() -> new OpsRejection("결제가 없습니다: " + paymentId));
        if (!payment.status().equals(NMC)) {
            throw new OpsRejection("NEEDS_MANUAL_CHECK인 결제만 처리합니다. 현재 상태는 " + payment.status() + "입니다");
        }
        if (inspection.findRefundByPaymentId(paymentId).isPresent()) {
            throw new OpsRejection("이 결제에는 이미 환불 행이 있습니다. 환불은 refund resolve로 다룹니다");
        }
        return payment;
    }

    // PG 결과 -> 전이. 결과를 모르거나 DB 기록과 어긋나면 거부한다
    private Decision decide(PaymentView payment, PgClient.PaymentLookupResult lookup) {
        return switch (lookup) {
            case PgClient.Approved approved -> afterApproval(payment, approved.paymentRef().value(), approved.amount(),
                    approved.currency(), approved.approvedAt());
            case PgClient.Declined declined -> {
                requireNoRecordedApproval(payment, "PG는 거절했다고 하는데");
                yield new Decision.Decline(declined.reason());
            }
            case PgClient.NotFound notFound -> {
                requireNoRecordedApproval(payment, "PG에서 주문을 찾을 수 없는데");
                yield new Decision.PreventLate();
            }
            case PgClient.Unknown unknown ->
                    throw new OpsRejection("PG 결과를 알 수 없어 아무것도 바꾸지 않는다(" + unknown.cause() + "). PG가 복구된 뒤 다시 한다");
        };
    }

    // PG가 승인했다(조회 또는 늦은 승인 방지가 알려 줌). 금액·통화가 같으면 승인, 다르면 환불을 시작한다(INV-3)
    private Decision afterApproval(PaymentView payment, String pgTxId, BigDecimal amount, String currency,
            Instant approvedAt) {
        if (payment.pgTxId() != null && !payment.pgTxId().equals(pgTxId)) {
            throw new OpsRejection("결제에 기록된 PG 거래 식별자가 PG의 것과 다릅니다. 기록=" + payment.pgTxId() + ", PG=" + pgTxId);
        }
        if (payment.currency().equals(currency) && payment.amount().compareTo(amount) == 0) {
            return new Decision.Approve(pgTxId, amount, approvedAt);
        }
        if (!payment.currency().equals(currency)) {
            throw new OpsRejection("PG 승인 통화(" + currency + ")가 결제 통화(" + payment.currency()
                    + ")와 달라 환불 금액을 정할 수 없습니다. 담당자와 확인한다");
        }
        if (payment.pgApprovedAmount() != null && payment.pgApprovedAmount().compareTo(amount) != 0) {
            throw new OpsRejection("결제에 기록된 PG 승인 금액(" + payment.pgApprovedAmount().toPlainString()
                    + ")이 PG의 금액(" + amount.toPlainString() + ")과 다릅니다");
        }
        return new Decision.StartRefund(pgTxId, amount, approvedAt);
    }

    private static void requireNoRecordedApproval(PaymentView payment, String prefix) {
        if (payment.pgTxId() != null) {
            throw new OpsRejection(prefix + " 결제에는 PG 거래가 기록되어 있습니다(" + payment.pgTxId() + "). 담당자와 확인한다");
        }
    }

    // 늦은 승인 방지는 PG에 흔적을 남기므로 적용 때만 부른다. 결과에 따라 전이가 정해진다
    private OpsPlan preventLateApproval(OpsTarget target, PaymentView payment, OpsApplyContext context) {
        var late = pg.preventLateApproval(payment.pgProvider(), new PgOrderId(payment.pgOrderId()));
        var decision = switch (late) {
            case PgClient.Prevented prevented -> new Decision.Decline(DeclineReason.NOT_SENT);
            case PgClient.AlreadyApproved approved -> afterApproval(payment, approved.paymentRef().value(),
                    approved.amount(), approved.currency(), approved.approvedAt());
            case PgClient.Unknown unknown ->
                    throw new OpsRejection("늦은 승인 방지 결과를 알 수 없어 아무것도 바꾸지 않는다(" + unknown.cause() + ")");
        };
        execute(payment, decision, context);
        return planOf(target, payment, PgEvidence.ofLateApproval(late), decision);
    }

    private void execute(PaymentView payment, Decision decision, OpsApplyContext context) {
        var changed = switch (decision) {
            case Decision.Approve approve -> transitions.approve(payment.id(), PaymentStatus.NEEDS_MANUAL_CHECK,
                    approve.pgTxId(), approve.amount(), approve.approvedAt(), context.actor());
            case Decision.Decline decline -> transitions.decline(payment.id(), PaymentStatus.NEEDS_MANUAL_CHECK,
                    decline.reason(), context.actor());
            case Decision.StartRefund refund -> refunds.createForAmountMismatch(payment.id(), refund.pgTxId(),
                    refund.amount(), refund.approvedAt(), context.actor()) instanceof RefundCreation.Created;
            case Decision.PreventLate preventLate -> throw new IllegalStateException("늦은 승인 방지는 따로 처리한다");
        };
        if (!changed) {
            throw new OpsRejection("조건부 UPDATE가 0행입니다. 그 사이 결제나 환불이 바뀌었습니다");
        }
    }

    private OpsPlan planOf(OpsTarget target, PaymentView payment, String evidence, Decision decision) {
        var snapshot = "status=" + payment.status() + " amount=" + payment.amount().toPlainString() + " "
                + payment.currency() + " pgOrderId=" + payment.pgOrderId() + " pgTxId=" + orNone(payment.pgTxId())
                + " pgApprovedAmount="
                + (payment.pgApprovedAmount() == null ? "-" : payment.pgApprovedAmount().toPlainString())
                + " refund=none";
        var details = new ArrayList<String>();
        switch (decision) {
            case Decision.Approve approve -> {
                details.add("PG 승인 금액 " + approve.amount().toPlainString() + " " + payment.currency()
                        + "이(가) 결제 금액과 같다");
                details.add("승인 시점에 예매가 이미 만료됐다면 Reservation이 RefundRequested를 보내 자동 환불로 이어진다(ADR 0027)");
                return new OpsPlan(target, snapshot, evidence, NMC + " -> APPROVED", NMC, "APPROVED",
                        List.of("PaymentApproved"), null, details);
            }
            case Decision.Decline decline -> {
                return new OpsPlan(target, snapshot, evidence, NMC + " -> DECLINED(" + decline.reason() + ")", NMC,
                        "DECLINED", List.of("PaymentDeclined"), null, details);
            }
            case Decision.StartRefund refund -> {
                details.add("PG 승인 금액 " + refund.amount().toPlainString() + " " + payment.currency()
                        + "이(가) 결제 금액 " + payment.amount().toPlainString() + "과 다르다(INV-3). 예매를 확정하지 않는다");
                details.add("결제는 " + NMC + "로 둔다. 환불 워커가 PG 환불을 끝내면 결제가 REFUNDED가 되고 그때 PaymentRefunded가 나간다");
                return new OpsPlan(target, snapshot, evidence,
                        "환불 REQUESTED 생성(reason=PG_AMOUNT_MISMATCH, 금액 " + refund.amount().toPlainString()
                                + "), 결제 " + NMC + " 유지", NMC, NMC, List.of(), null, details);
            }
            case Decision.PreventLate preventLate -> {
                details.add("PG가 주문을 찾지 못했다. 적용 때 다시 조회해 여전히 없으면 늦은 승인 방지를 부른다(dry-run은 PG에 "
                        + "흔적을 남기므로 부르지 않았다)");
                details.add("방지에 성공하면 DECLINED(NOT_SENT). PG가 이미 승인했다고 하면 금액을 대조해 APPROVED 또는 환불 시작으로 바뀐다");
                return new OpsPlan(target, snapshot, evidence,
                        NMC + " -> DECLINED(NOT_SENT) 시도(적용 때 늦은 승인 방지. 이미 승인됐으면 APPROVED 또는 환불 시작)", NMC,
                        "DECLINED", List.of("PaymentDeclined(방지에 성공하면)"), null, details);
            }
        }
    }

    private static String orNone(String value) {
        return value == null ? "-" : value;
    }

    private sealed interface Decision {
        record Approve(String pgTxId, BigDecimal amount, Instant approvedAt) implements Decision {}

        record Decline(DeclineReason reason) implements Decision {}

        record StartRefund(String pgTxId, BigDecimal amount, Instant approvedAt) implements Decision {}

        record PreventLate() implements Decision {}
    }
}
