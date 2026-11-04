package com.iseonjwa.payment.ops.application;

import com.iseonjwa.payment.ops.domain.OpsInspectionRepository;
import com.iseonjwa.payment.ops.domain.OpsInspectionRepository.PaymentView;
import com.iseonjwa.payment.ops.domain.OpsInspectionRepository.RefundView;
import com.iseonjwa.payment.ops.domain.OpsTargetType;
import com.iseonjwa.payment.pg.application.PgClient;
import com.iseonjwa.payment.pg.application.PgPaymentRef;
import com.iseonjwa.payment.pg.application.PgRefundId;
import com.iseonjwa.payment.refund.application.RefundRetryService;
import com.iseonjwa.payment.refund.application.RefundSettlementService;
import com.iseonjwa.payment.refund.domain.RefundReason;
import com.iseonjwa.payment.refund.domain.RefundStatus;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * {@code refund retry}: {@code NEEDS_MANUAL_CHECK} 환불을 {@code REQUESTED}로 되돌려 환불 워커가 이어받게 한다
 * (manual-recovery.md 2절). 4-eyes 명령이다.
 *
 * <p>계획을 만들 때 {@code findRefund(pg_refund_id, paymentRef)}로 PG를 확인하고 그 결과가 계획을 정한다.
 * {@code NotFound}·{@code RefundRejected}면 다시 시도할 수 있다. {@code Refunded}면 이미 환불됐으므로
 * {@code refund resolve}를 쓰게 하고, {@code Unknown}이면 PG 장애 중에는 다시 시도해도 같아서 거부한다.
 * 결제도 확인한다. 환불 사유에 맞는 상태가 아니거나 {@code pg_tx_id}가 없으면 되돌려도 워커가 곧바로
 * {@code STATE_CONFLICT}로 다시 넘기므로 거부한다.
 *
 * <p>Note: 이 명령은 {@code PgClient.refund}를 부르지 않는다(ADR 0028 결정 4). 환불만 {@code REQUESTED}로 돌려놓고 PG 환불은
 * 워커가 한다.
 *
 * <p>Note: {@code pg_refund_id}와 {@code pg_requested_at}을 그대로 둔다. {@code pg_requested_at}이 남아 있으면 워커가
 * {@code findRefund}부터 시작하고 {@code NotFound}일 때만 같은 {@code pg_refund_id}로 {@code refund}를 부른다. 비우면
 * 워커가 {@code findRefund} 없이 {@code refund}부터 불러 이중 환불 방어가 PG 멱등키 하나에만 기대게 된다. 이 명령이 계획 때
 * 본 {@code NotFound}가 적용 시점까지 이어진다는 보장도 없다(그사이 PG에서 환불이 끝날 수 있다).
 */
@Component
public class RefundRetryCommand implements OpsCommand {
    public static final String NAME = "refund retry";
    private static final String NMC = RefundStatus.NEEDS_MANUAL_CHECK.name();
    private static final String REQUESTED = RefundStatus.REQUESTED.name();

    private final OpsInspectionRepository inspection;
    private final OpsPgLookup pg;
    private final RefundRetryService retry;

    public RefundRetryCommand(OpsInspectionRepository inspection, OpsPgLookup pg, RefundRetryService retry) {
        this.inspection = inspection;
        this.pg = pg;
        this.retry = retry;
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
        return Set.of("refund-id");
    }

    @Override
    public OpsTarget target(OpsArguments arguments) {
        return new OpsTarget(OpsTargetType.REFUND, arguments.requireLong("refund-id"));
    }

    @Override
    public OpsPlan plan(OpsArguments arguments) {
        var target = target(arguments);
        var found = load(target.id());
        var lookup = lookup(found);
        requireRetryable(lookup);
        return planOf(target, found, lookup);
    }

    @Override
    public OpsPlan apply(OpsPlan plan, OpsApplyContext context) {
        var found = load(plan.target().id());
        var lookup = lookup(found);
        if (!PgEvidence.of(lookup).equals(plan.pgEvidence())) {
            throw new OpsRejection("PG 근거가 계획을 만든 뒤 바뀌었습니다. dry-run을 다시 실행한다");
        }
        if (!retry.requeue(found.refund().id(), found.refund().paymentId(), context.actor())) {
            throw new OpsRejection("조건부 UPDATE가 0행입니다. 그 사이 환불이 " + NMC + "가 아니게 되었습니다");
        }
        return plan;
    }

    // 환불과 결제를 읽고 워커가 곧바로 다시 멈추게 될 상태는 PG를 부르기 전에 거른다
    private Found load(long refundId) {
        var paymentId = inspection.findPaymentIdByRefundId(refundId)
                .orElseThrow(() -> new OpsRejection("환불이 없습니다: " + refundId));
        var refund = inspection.findRefundByPaymentId(paymentId).orElseThrow();
        if (!refund.status().equals(NMC)) {
            throw new OpsRejection(NMC + "인 환불만 처리합니다. 현재 상태는 " + refund.status() + "입니다");
        }
        var payment = inspection.findPayment(paymentId).orElseThrow();
        var expected = RefundSettlementService.paymentStatusBeforeRefund(RefundReason.valueOf(refund.reason()));
        if (!payment.status().equals(expected.name())) {
            throw new OpsRejection("결제가 " + expected + " 상태가 아니라 되돌려도 환불 워커가 다시 멈춥니다(STATE_CONFLICT). "
                    + "현재 결제 상태는 " + payment.status() + "입니다. 원인을 먼저 확인한다");
        }
        if (payment.pgTxId() == null) {
            throw new OpsRejection("결제에 PG 거래 식별자가 없어 되돌려도 환불 워커가 다시 멈춥니다(STATE_CONFLICT). 원인을 먼저 확인한다");
        }
        return new Found(refund, payment);
    }

    private PgClient.RefundLookupResult lookup(Found found) {
        return pg.findRefund(new PgRefundId(found.refund().pgRefundId()), new PgPaymentRef(found.payment().pgTxId()));
    }

    private static void requireRetryable(PgClient.RefundLookupResult lookup) {
        switch (lookup) {
            case PgClient.NotFound notFound -> { }
            case PgClient.RefundRejected rejected -> { }
            case PgClient.Refunded refunded -> throw new OpsRejection("PG에서 이미 환불이 끝났습니다(환불 거래 "
                    + refunded.refundRef() + "). 다시 시도하지 않는다. refund resolve로 환불 완료를 반영한다");
            case PgClient.Unknown unknown -> throw new OpsRejection("PG 결과를 알 수 없어 아무것도 바꾸지 않는다("
                    + unknown.cause() + "). PG 장애 중에는 다시 시도해도 같다. PG가 복구된 뒤 다시 한다");
        }
    }

    private OpsPlan planOf(OpsTarget target, Found found, PgClient.RefundLookupResult lookup) {
        var refund = found.refund();
        var payment = found.payment();
        var snapshot = "refundStatus=" + refund.status() + " amount=" + refund.amount().toPlainString() + " "
                + payment.currency() + " reason=" + refund.reason() + " pgRefundId=" + refund.pgRefundId() + " attempt="
                + refund.attempt() + " pgRequestedAt=" + (refund.pgRequestedAt() == null ? "-" : "set")
                + " paymentStatus=" + payment.status() + " pgTxId=" + payment.pgTxId();
        var details = new java.util.ArrayList<String>();
        if (lookup instanceof PgClient.RefundRejected rejected) {
            details.add("PG가 이전 환불을 거절했다(" + rejected.reason() + "). 거절 원인을 고쳤는지 확인한 뒤 적용한다");
        } else {
            details.add("PG에 이 환불의 기록이 없다. 원인(PG 장애, 결제 상태)이 풀렸는지 확인한 뒤 적용한다");
        }
        details.add("pg_refund_id(PG 멱등키)와 pg_requested_at은 그대로 둔다. 워커가 findRefund로 먼저 확인하고 "
                + "NotFound일 때만 같은 pg_refund_id로 환불을 부른다");
        details.add("이 명령은 PG 환불을 부르지 않는다. 환불 워커가 다음 주기에 이어받는다");
        return new OpsPlan(target, snapshot, PgEvidence.of(lookup),
                "환불 " + NMC + " -> " + REQUESTED + " (attempt 0, next_check_at=실행 시각, pg_refund_id·pg_requested_at 유지)",
                NMC, REQUESTED, List.of(), null, details);
    }

    private record Found(RefundView refund, PaymentView payment) {}
}
