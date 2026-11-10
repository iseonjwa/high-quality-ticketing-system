package com.iseonjwa.payment.ops.application;

import com.iseonjwa.payment.ops.domain.OpsInspectionRepository;
import com.iseonjwa.payment.ops.domain.OpsInspectionRepository.PaymentView;
import com.iseonjwa.payment.ops.domain.OpsInspectionRepository.RefundView;
import com.iseonjwa.payment.ops.domain.OpsTargetType;
import com.iseonjwa.payment.pg.application.PgClient;
import com.iseonjwa.payment.pg.application.PgPaymentRef;
import com.iseonjwa.payment.pg.application.PgRefundId;
import com.iseonjwa.payment.refund.application.RefundSettlementService;
import com.iseonjwa.payment.refund.domain.RefundReason;
import com.iseonjwa.payment.refund.domain.RefundStatus;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * {@code refund resolve}: {@code NEEDS_MANUAL_CHECK} 환불을 PG 근거로 닫는다(manual-recovery.md 2·8절). 계획을 만들 때
 * {@code findRefund(pg_refund_id, paymentRef)}로 PG를 다시 조회하고 그 결과가 전이를 정한다. 4-eyes 명령이다.
 *
 * <p>PG 결과별 전이: 환불 완료이고 금액·통화가 같으면 환불·결제 {@code REFUNDED} + {@code PaymentRefunded}(결제의 출발 상태는
 * 워커와 같은 규칙), 거절이고 {@code --close-failed --external-ref}가 함께 있으면 환불 {@code FAILED}(이벤트 없음, 결제 상태
 * 유지). 그 밖에는 거부한다. 거부는 안내 문구를 함께 낸다.
 *
 * <p>Note: 이 명령은 {@code PgClient.refund}를 부르지 않는다(ADR 0028 결정 4). 환불 재시도는 {@code refund retry}
 * (ISE-160)로 환불을 {@code REQUESTED}로 되돌려 워커가 한다. 이 명령은 PG가 이미 한 일을 DB에 반영하거나, 사람이 외부에서 한
 * 조치를 근거와 함께 기록해 닫는 일만 한다.
 *
 * <p>Note: PG 콘솔에서 직접 환불한 경우(manual-recovery.md 8절)는 우리 {@code pg_refund_id}로 일어난 환불이 아니라서 지금의
 * {@code findRefund}로는 확인되지 않는다. 이 명령은 {@code NotFound}로 거부하고 환불은 {@code NEEDS_MANUAL_CHECK}로
 * 남는다(ADR 0028 결정 11의 "PG API로 확인할 수 없으면 거부"). 콘솔 환불을 확인하는 PG 조회는 토스 어댑터를 만들 때 다시 본다.
 *
 * <p>Note: {@code --external-ref}는 콘솔 환불이나 계좌 환불 같은 외부 조치의 참조다. 환불 완료에서는 감사 기록에만 남기고,
 * 종결({@code FAILED})에서는 필수다. 해시의 스냅숏에 넣어 인자가 바뀌면 확인 값이 달라지게 한다. 적용 때 PG를 한 번 더 조회해
 * 계획의 근거와 같은지 확인한다.
 */
@Component
public class RefundResolveCommand implements OpsCommand {
    public static final String NAME = "refund resolve";
    private static final String NMC = RefundStatus.NEEDS_MANUAL_CHECK.name();
    private static final int EXTERNAL_REF_MAX_LENGTH = 200;
    private static final String CLOSE_FAILED_HISTORY_REASON = "OPS_CLOSED";

    private final OpsInspectionRepository inspection;
    private final OpsPgLookup pg;
    private final RefundSettlementService settlement;

    public RefundResolveCommand(OpsInspectionRepository inspection, OpsPgLookup pg, RefundSettlementService settlement) {
        this.inspection = inspection;
        this.pg = pg;
        this.settlement = settlement;
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
        return Set.of("refund-id", "external-ref", "close-failed");
    }

    @Override
    public OpsTarget target(OpsArguments arguments) {
        return new OpsTarget(OpsTargetType.REFUND, arguments.requireLong("refund-id"));
    }

    @Override
    public OpsPlan plan(OpsArguments arguments) {
        var target = target(arguments);
        var externalRef = externalRef(arguments);
        var closeFailed = arguments.flag("close-failed");
        var found = load(target.id());
        var lookup = pg.findRefund(found.payment().pgProvider(), new PgRefundId(found.refund().pgRefundId()), paymentRef(found.payment()));
        return planOf(target, found, closeFailed, externalRef, PgEvidence.of(lookup),
                decide(found, lookup, closeFailed, externalRef));
    }

    @Override
    public OpsPlan apply(OpsPlan plan, OpsApplyContext context) {
        var found = load(plan.target().id());
        var lookup = pg.findRefund(found.payment().pgProvider(), new PgRefundId(found.refund().pgRefundId()), paymentRef(found.payment()));
        if (!PgEvidence.of(lookup).equals(plan.pgEvidence())) {
            throw new OpsRejection("PG 근거가 계획을 만든 뒤 바뀌었습니다. dry-run을 다시 실행한다");
        }
        // 해시가 같으면 인자도 같다. 계획에서 인자를 다시 꺼내지 않고 계획의 전이를 그대로 실행한다
        var decision = decide(found, lookup, plan.toStatus().equals("FAILED"), plan.externalRef());
        var refund = found.refund();
        switch (decision) {
            case Decision.Complete complete -> {
                var outcome = settlement.complete(refund.id(), refund.paymentId(), RefundStatus.NEEDS_MANUAL_CHECK,
                        RefundReason.valueOf(refund.reason()), complete.refundRef(), complete.refundedAt(),
                        context.actor());
                switch (outcome) {
                    case COMPLETED -> { }
                    case REFUND_CHANGED -> throw new OpsRejection("조건부 UPDATE가 0행입니다. 그 사이 환불이 NEEDS_MANUAL_CHECK가 아니게 되었습니다");
                    case PAYMENT_NOT_REFUNDABLE -> throw new OpsRejection("결제가 환불을 반영할 수 있는 상태가 아닙니다(0행). 롤백한다");
                }
            }
            case Decision.CloseFailed close -> {
                if (!settlement.closeAsFailed(refund.id(), refund.paymentId(), RefundStatus.NEEDS_MANUAL_CHECK,
                        CLOSE_FAILED_HISTORY_REASON, context.actor())) {
                    throw new OpsRejection("조건부 UPDATE가 0행입니다. 그 사이 환불이 NEEDS_MANUAL_CHECK가 아니게 되었습니다");
                }
            }
        }
        return plan;
    }

    private Found load(long refundId) {
        var paymentId = inspection.findPaymentIdByRefundId(refundId)
                .orElseThrow(() -> new OpsRejection("환불이 없습니다: " + refundId));
        var refund = inspection.findRefundByPaymentId(paymentId).orElseThrow();
        if (!refund.status().equals(NMC)) {
            throw new OpsRejection("NEEDS_MANUAL_CHECK인 환불만 처리합니다. 현재 상태는 " + refund.status() + "입니다");
        }
        return new Found(refund, inspection.findPayment(paymentId).orElseThrow());
    }

    private static PgPaymentRef paymentRef(PaymentView payment) {
        if (payment.pgTxId() == null) {
            throw new OpsRejection("결제에 PG 거래 식별자가 없어 환불을 조회할 수 없습니다");
        }
        return new PgPaymentRef(payment.pgTxId());
    }

    private static String externalRef(OpsArguments arguments) {
        var value = arguments.value("external-ref");
        if (value != null && value.length() > EXTERNAL_REF_MAX_LENGTH) {
            throw new OpsUsageException("--external-ref는 " + EXTERNAL_REF_MAX_LENGTH + "자 이하여야 합니다");
        }
        return value;
    }

    // PG 결과 -> 전이. 근거가 없거나 어긋나면 안내와 함께 거부한다
    private Decision decide(Found found, PgClient.RefundLookupResult lookup, boolean closeFailed, String externalRef) {
        var refund = found.refund();
        return switch (lookup) {
            case PgClient.Refunded refunded -> {
                if (closeFailed) {
                    throw new OpsRejection("--close-failed는 PG가 환불을 거절한 경우에만 씁니다. PG는 환불이 끝났다고 합니다");
                }
                if (!refunded.matches(refund.amount(), found.payment().currency())) {
                    throw new OpsRejection("PG의 환불 금액(" + refunded.amount().toPlainString() + " " + refunded.currency()
                            + ")이 환불 금액(" + refund.amount().toPlainString() + " " + found.payment().currency()
                            + ")과 다릅니다. 실제 환불 금액이 다를 때의 종결 절차는 아직 없습니다. 담당자와 확인한다");
                }
                requireSettleable(found);
                yield new Decision.Complete(refunded.refundRef(), refunded.refundedAt());
            }
            case PgClient.RefundRejected rejected -> {
                if (!closeFailed || externalRef == null) {
                    throw new OpsRejection("PG가 환불을 거절했습니다(" + rejected.reason() + "). 고칠 수 있으면 고친 뒤 refund retry"
                            + "(ISE-160)를 쓴다. PG로 환불할 수 없어 외부 조치(고객 계좌 환불 등)를 했다면 --close-failed와 "
                            + "--external-ref=<조치 참조>를 함께 지정한다");
                }
                yield new Decision.CloseFailed();
            }
            case PgClient.NotFound notFound -> throw new OpsRejection("PG에 환불 기록이 없습니다"
                    + (closeFailed ? "(--close-failed는 PG가 거절한 경우에만 쓴다)" : "") + ". 원인이 풀렸으면 refund retry(ISE-160)를 쓴다. "
                    + "PG 콘솔에서 직접 환불했더라도 이 명령은 우리 pg_refund_id로만 조회하므로 확인하지 못한다(manual-recovery.md 8절)");
            case PgClient.Unknown unknown ->
                    throw new OpsRejection("PG 결과를 알 수 없어 아무것도 바꾸지 않는다(" + unknown.cause() + "). PG가 복구된 뒤 다시 한다");
        };
    }

    // 환불 완료를 결제에 반영할 수 없으면(STATE_CONFLICT 같은 경우) 적용 전에 알려 준다
    private static void requireSettleable(Found found) {
        var expected = RefundSettlementService.paymentStatusBeforeRefund(RefundReason.valueOf(found.refund().reason()));
        if (!found.payment().status().equals(expected.name())) {
            throw new OpsRejection("결제가 " + expected + " 상태가 아니라 환불 완료를 반영할 수 없습니다. 현재 결제 상태는 "
                    + found.payment().status() + "입니다. 원인을 먼저 확인한다");
        }
    }

    private OpsPlan planOf(OpsTarget target, Found found, boolean closeFailed, String externalRef, String evidence,
            Decision decision) {
        var refund = found.refund();
        var payment = found.payment();
        var snapshot = "refundStatus=" + refund.status() + " amount=" + refund.amount().toPlainString() + " "
                + payment.currency() + " reason=" + refund.reason() + " pgRefundId=" + refund.pgRefundId() + " attempt="
                + refund.attempt() + " paymentStatus=" + payment.status() + " pgTxId=" + payment.pgTxId()
                + " closeFailed=" + closeFailed + " externalRef=" + (externalRef == null ? "-" : externalRef);
        var details = new java.util.ArrayList<String>();
        switch (decision) {
            case Decision.Complete complete -> {
                var before = RefundSettlementService.paymentStatusBeforeRefund(RefundReason.valueOf(refund.reason()));
                if (externalRef != null) {
                    details.add("외부 조치 참조(감사 기록에만 남는다): " + externalRef);
                }
                return new OpsPlan(target, snapshot, evidence,
                        "환불 " + NMC + " -> REFUNDED, 결제 " + before + " -> REFUNDED", NMC, "REFUNDED",
                        List.of("PaymentRefunded"), externalRef, details);
            }
            case Decision.CloseFailed close -> {
                details.add("PG가 환불을 거절했다. 고객 환불은 외부 조치(" + externalRef + ")로 끝났음을 기록한다");
                details.add("결제 상태는 " + payment.status() + " 그대로이고 이벤트는 나가지 않는다");
                return new OpsPlan(target, snapshot, evidence, "환불 " + NMC + " -> FAILED (결제 상태 유지)", NMC, "FAILED",
                        List.of(), externalRef, details);
            }
        }
    }

    private record Found(RefundView refund, PaymentView payment) {}

    private sealed interface Decision {
        record Complete(String refundRef, Instant refundedAt) implements Decision {}

        record CloseFailed() implements Decision {}
    }
}
