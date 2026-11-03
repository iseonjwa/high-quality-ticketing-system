package com.iseonjwa.payment.refund.application;

import com.iseonjwa.payment.observability.PaymentMetrics;
import com.iseonjwa.payment.observability.PgOperation;
import com.iseonjwa.payment.observability.TransitionOperation;
import com.iseonjwa.payment.payment.application.PaymentTransitionService;
import com.iseonjwa.payment.payment.application.RecheckSchedule;
import com.iseonjwa.payment.payment.domain.PaymentRepository;
import com.iseonjwa.payment.payment.domain.PaymentStatus;
import com.iseonjwa.payment.payment.domain.PaymentStatusHistory;
import com.iseonjwa.payment.payment.domain.PaymentStatusHistoryRepository;
import com.iseonjwa.payment.payment.domain.StatusActor;
import com.iseonjwa.payment.pg.application.PgClient;
import com.iseonjwa.payment.pg.application.PgIdempotencyKey;
import com.iseonjwa.payment.pg.application.PgPaymentRef;
import com.iseonjwa.payment.pg.application.PgPermit;
import com.iseonjwa.payment.pg.application.PgRecoveryGate;
import com.iseonjwa.payment.pg.application.PgRefundId;
import com.iseonjwa.payment.pg.application.RefundCommand;
import com.iseonjwa.payment.pg.application.UnknownCause;
import com.iseonjwa.payment.refund.domain.RefundReason;
import com.iseonjwa.payment.refund.domain.RefundRepository;
import com.iseonjwa.payment.refund.domain.RefundStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 자동 환불 한 건의 처리(events-and-reliability.md 6절). 차례가 된 {@code REQUESTED}·{@code UNKNOWN} 환불 하나를 맡아(claim)
 * PG에 환불을 요청하거나 조회하고 결과로 확정한다. 순서는 후보 잠금 → PG 허가 → 임대 → (트랜잭션 밖) PG 호출 → 결과 반영이다.
 * 결제 재확인({@code PaymentRecheckService})과 같은 구조다.
 *
 * <p>Note: 호출 선택은 {@code pg_requested_at}으로 한다. claim이 이 값을 처음에만 채우므로, claim 전에 비어 있었다면 PG를
 * 처음 부르는 것이라 {@code refund}를 바로 부른다. 값이 있으면 이전에 이미 요청을 보냈을 수 있어(응답 유실, 서버 종료)
 * {@code findRefund}로 먼저 확인하고 {@code NotFound}일 때만 같은 {@code pg_refund_id}로 {@code refund}를 다시 부른다.
 * 이중 환불을 막는 것은 이 순서와 PG 멱등키다(INV-2).
 *
 * <p>Note: 허가를 claim보다 먼저 얻는다. 허가를 못 얻은 주기는 아무것도 바꾸지 않아 시도 횟수를 세지 않는다.
 * PG 호출은 트랜잭션 밖이라 DB 연결을 잡지 않는다.
 */
@Service
public class RefundWorkerService {
    // claim한 워커가 결과를 반영하기 전에 죽어도 이 시간이 지나면 다시 대상이 된다(7절). PG 호출 시한보다 충분히 길게 둔다
    private static final Duration LEASE = Duration.ofSeconds(30);
    private static final String PERMIT_DENIED = "RECOVERY_PERMIT_DENIED";
    private static final String STATE_CONFLICT = "STATE_CONFLICT";

    private static final Logger log = LoggerFactory.getLogger(RefundWorkerService.class);

    private final RefundRepository refunds;
    private final PaymentRepository payments;
    private final PaymentStatusHistoryRepository histories;
    private final PaymentTransitionService transitions;
    private final PgRecoveryGate recoveryGate;
    private final PgClient pg;
    private final PaymentMetrics metrics;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    public RefundWorkerService(RefundRepository refunds, PaymentRepository payments,
            PaymentStatusHistoryRepository histories, PaymentTransitionService transitions, PgRecoveryGate recoveryGate,
            PgClient pg, PaymentMetrics metrics, TransactionTemplate transactionTemplate, @Qualifier("commonAuditingClock") Clock clock) {
        this.refunds = refunds;
        this.payments = payments;
        this.histories = histories;
        this.transitions = transitions;
        this.recoveryGate = recoveryGate;
        this.pg = pg;
        this.metrics = metrics;
        this.transactionTemplate = transactionTemplate;
        this.clock = clock;
    }

    /**
     * 환불할 차례가 된 환불 하나를 처리한다.
     *
     * @return 처리했으면 true. 할 일이 없거나 PG 복구 허가를 못 얻었으면 false다(호출한 쪽이 이번 주기를 끝낸다)
     */
    public boolean processNext() {
        var claim = claimNext();
        if (claim.isEmpty()) return false;
        try (var ignored = claim.get().permit()) {
            resolve(claim.get());
        } catch (RuntimeException | Error exception) {
            // 주기 로그에는 환불 id가 없어서, 같은 건이 반복 실패해도 어느 건인지 알 수 없다
            log.error("환불 처리 실패 refundId={} paymentId={}", claim.get().refundId(), claim.get().paymentId(),
                    exception);
            throw exception;
        }
        return true;
    }

    // 후보 잠금 → 허가 → 임대를 한 트랜잭션으로 한다. 허가를 못 얻으면 아무것도 바꾸지 않고 끝난다
    private Optional<Claim> claimNext() {
        var heldPermit = new AtomicReference<PgPermit>();
        var claimed = false;
        try {
            var claim = transactionTemplate.execute(status -> claimInTransaction(heldPermit));
            claimed = claim.isPresent();
            return claim;
        } finally {
            // Note: finally라서 Error가 나도 허가가 새지 않는다. 임대 커밋이 실패했거나 임대하지 못했으면 호출하지 않은
            // 허가를 돌려주고(호출 결과로 세지 않는다), 정상 claim이면 processNext가 받아 쓴다
            if (!claimed) closeIfHeld(heldPermit);
        }
    }

    private Optional<Claim> claimInTransaction(AtomicReference<PgPermit> heldPermit) {
        var now = clock.instant();
        var candidate = refunds.findDueForUpdate(now);
        if (candidate.isEmpty()) return Optional.empty();

        var permit = recoveryGate.tryAcquire();
        if (permit.isEmpty()) return Optional.empty();
        heldPermit.set(permit.get());

        // 임대하면 엔티티가 영속성 컨텍스트에서 분리되므로 필요한 값을 먼저 읽는다
        var refund = candidate.get();
        var payment = payments.findById(refund.getPaymentId()).orElseThrow();
        if (payment.getStatus() != paymentStatusBeforeRefund(refund.getReason()) || payment.getPgTxId() == null) {
            // PG를 부르기 전에 결제가 환불할 수 없는 상태다. 환불 완료를 반영할 수 없으므로 부르지 않고 운영자에게 넘긴다
            log.error("event=REFUND_STATE_CONFLICT refundId={} paymentId={} paymentStatus={}", refund.getId(),
                    payment.getId(), payment.getStatus());
            markNeedsManualCheck(refund.getId(), refund.getPaymentId(), refund.getStatus(), STATE_CONFLICT);
            return Optional.empty();
        }
        var claim = new Claim(refund.getId(), refund.getPaymentId(), refund.getStatus(),
                new PgRefundId(refund.getPgRefundId()), refund.getAmount(), refund.getReason(),
                refund.getPgRequestedAt() == null, refund.getAttempt() + 1, new PgPaymentRef(payment.getPgTxId()),
                payment.getCurrency(), permit.get());
        if (!refunds.claim(refund.getId(), refund.getStatus(), now.plus(LEASE), now)) return Optional.empty();
        return Optional.of(claim);
    }

    private static void closeIfHeld(AtomicReference<PgPermit> heldPermit) {
        var held = heldPermit.get();
        if (held != null) held.close();
    }

    // 환불 완료가 결제를 옮기기 전의 상태. 금액 불일치 환불(Ops 명령이 시작)만 운영자 확인 상태의 결제를 환불한다
    private static PaymentStatus paymentStatusBeforeRefund(RefundReason reason) {
        return reason == RefundReason.PG_AMOUNT_MISMATCH ? PaymentStatus.NEEDS_MANUAL_CHECK : PaymentStatus.APPROVED;
    }

    private void resolve(Claim claim) {
        var permit = claim.permit();
        permit.callStarted();
        if (claim.firstCall()) {
            var result = requestRefund(claim);
            permit.settle(result);
            applyRefundResult(claim, PgOperation.REFUND, result);
            return;
        }
        var lookup = findRefund(claim);
        permit.settle(lookup);
        logClientError(claim, lookup);
        switch (lookup) {
            case PgClient.Refunded refunded -> confirm(claim, PgOperation.FIND_REFUND, refunded);
            case PgClient.RefundRejected rejected -> reject(claim, rejected);
            case PgClient.NotFound notFound -> requestAgain(claim);
            case PgClient.Unknown unknown -> handleUnknown(claim, unknown.cause().name());
        }
    }

    private void applyRefundResult(Claim claim, PgOperation operation, PgClient.RefundResult result) {
        logClientError(claim, result);
        switch (result) {
            case PgClient.Refunded refunded -> confirm(claim, operation, refunded);
            case PgClient.RefundRejected rejected -> reject(claim, rejected);
            case PgClient.Unknown unknown -> handleUnknown(claim, unknown.cause().name());
        }
    }

    // 조회에서 PG가 환불을 모른다고 했다. 요청이 닿지 않았을 수 있으니 같은 환불 ID로 다시 요청한다(멱등키가 같다)
    private void requestAgain(Claim claim) {
        var permit = recoveryGate.tryAcquire();
        if (permit.isEmpty()) {
            handleUnknown(claim, PERMIT_DENIED);
            return;
        }
        try (var held = permit.get()) {
            held.callStarted();
            var result = requestRefund(claim);
            held.settle(result);
            applyRefundResult(claim, PgOperation.REFUND, result);
        }
    }

    // 포트는 결과를 예외가 아닌 타입으로 돌려주지만, 예외가 나오면 결과를 모르는 것(Unknown)으로 다룬다
    private PgClient.RefundResult requestRefund(Claim claim) {
        try {
            var command = new RefundCommand(claim.paymentRef(), claim.pgRefundId(), claim.amount(), claim.currency(),
                    claim.reason().name(), PgIdempotencyKey.ofRefund(claim.pgRefundId()));
            var result = pg.refund(command);
            if (result != null) return result;
            log.error("PG 환불 결과가 null이다 refundId={}", claim.refundId());
            return new PgClient.Unknown(UnknownCause.PG_ERROR);
        } catch (RuntimeException exception) {
            log.error("PG 환불 중 예외 refundId={}", claim.refundId(), exception);
            return new PgClient.Unknown(UnknownCause.PG_ERROR);
        }
    }

    private PgClient.RefundLookupResult findRefund(Claim claim) {
        try {
            var result = pg.findRefund(claim.pgRefundId(), claim.paymentRef());
            if (result != null) return result;
            log.error("PG 환불 조회 결과가 null이다 refundId={}", claim.refundId());
            return new PgClient.Unknown(UnknownCause.PG_ERROR);
        } catch (RuntimeException exception) {
            log.error("PG 환불 조회 중 예외 refundId={}", claim.refundId(), exception);
            return new PgClient.Unknown(UnknownCause.PG_ERROR);
        }
    }

    // PG가 환불했다. 금액·통화가 환불과 같으면 확정하고, 다르면 운영자 확인으로 넘긴다(INV-3)
    private void confirm(Claim claim, PgOperation operation, PgClient.Refunded refunded) {
        if (!refunded.matches(claim.amount(), claim.currency())) {
            // 즉시 알림은 PaymentPgAmountMismatch 규칙(infra/prometheus/rules/payment-alerts.yml)이 낸다(INV-3)
            metrics.pgAmountMismatch(operation);
            log.error("event=REFUND_AMOUNT_MISMATCH refundId={} paymentId={} expectedAmount={} pgAmount={}",
                    claim.refundId(), claim.paymentId(), claim.amount(), refunded.amount());
            markNeedsManualCheck(claim.refundId(), claim.paymentId(), claim.status(), "REFUND_AMOUNT_MISMATCH");
            return;
        }
        try {
            transactionTemplate.executeWithoutResult(status -> complete(claim, refunded));
        } catch (StateConflictException conflict) {
            // 결제가 환불할 수 없는 상태로 바뀌어 있었다. 환불 전이까지 롤백됐으므로 새 트랜잭션에서 운영자 확인으로 넘긴다
            // 즉시 알림은 RefundNeedsManualCheck 규칙이 낸다. 건수는 gauge-refresh가 센다
            log.error("event=REFUND_STATE_CONFLICT refundId={} paymentId={} pgRefundRef={}", claim.refundId(),
                    claim.paymentId(), refunded.refundRef());
            markNeedsManualCheck(claim.refundId(), claim.paymentId(), claim.status(), STATE_CONFLICT);
        }
    }

    // 환불 → 결제 순으로 한 트랜잭션에서 바꾼다. 환불이 0행이면 다른 경로가 먼저 바꾼 것이라 더 쓰지 않는다
    private void complete(Claim claim, PgClient.Refunded refunded) {
        var now = clock.instant();
        if (!refunds.markRefunded(claim.refundId(), claim.status(), refunded.refundRef(), refunded.refundedAt(), now)) {
            metrics.transitionConflict(TransitionOperation.REFUND_WORKER);
            return;
        }
        histories.save(PaymentStatusHistory.ofRefund(claim.paymentId(), claim.status(), RefundStatus.REFUNDED, null,
                StatusActor.SYSTEM, now));
        if (!transitions.markRefunded(claim.paymentId(), paymentStatusBeforeRefund(claim.reason()),
                refunded.refundedAt(), StatusActor.SYSTEM)) {
            metrics.transitionConflict(TransitionOperation.REFUND_WORKER);
            // 예외로 이 트랜잭션을 롤백한다. 환불만 REFUNDED로 남으면 돈은 돌아갔는데 결제는 환불되지 않은 모순이 된다
            throw new StateConflictException();
        }
    }

    // PG가 환불할 수 없다고 명확히 거절했다. FAILED는 운영자가 확인한 뒤에만 쓴다(domain-and-states.md 2절)
    private void reject(Claim claim, PgClient.RefundRejected rejected) {
        // 즉시 알림은 RefundNeedsManualCheck 규칙이 낸다. 건수는 gauge-refresh가 센다
        log.error("event=REFUND_REJECTED refundId={} paymentId={} rejectReason={}", claim.refundId(), claim.paymentId(),
                rejected.reason());
        markNeedsManualCheck(claim.refundId(), claim.paymentId(), claim.status(), "REFUND_REJECTED");
    }

    // 결과를 모른다. 결제 재확인과 같은 간격으로 다시 확인하고, 소진하면 운영자 확인으로 넘긴다(SA 9-4)
    private void handleUnknown(Claim claim, String cause) {
        var delay = RecheckSchedule.delayAfter(claim.attempts());
        if (delay.isEmpty()) {
            // 즉시 알림은 RefundNeedsManualCheck 규칙이 낸다. 건수는 gauge-refresh가 센다
            log.error("event=REFUND_UNRESOLVED refundId={} paymentId={} attempts={} lastCause={}", claim.refundId(),
                    claim.paymentId(), claim.attempts(), cause);
            markNeedsManualCheck(claim.refundId(), claim.paymentId(), claim.status(), "REFUND_UNRESOLVED");
            return;
        }
        var now = clock.instant();
        var nextCheckAt = now.plus(delay.get());
        if (claim.status() == RefundStatus.REQUESTED) {
            transactionTemplate.executeWithoutResult(status -> {
                if (!refunds.markUnknown(claim.refundId(), nextCheckAt, now)) {
                    metrics.transitionConflict(TransitionOperation.REFUND_WORKER);
                    return;
                }
                histories.save(PaymentStatusHistory.ofRefund(claim.paymentId(), RefundStatus.REQUESTED,
                        RefundStatus.UNKNOWN, cause, StatusActor.SYSTEM, now));
            });
        } else {
            refunds.reschedule(claim.refundId(), nextCheckAt, now);
        }
    }

    // 환불을 운영자 확인으로 넘기고 이력에 사유를 남긴다. 0행이면 다른 경로가 먼저 바꾼 것이라 이력을 쓰지 않는다
    private void markNeedsManualCheck(Long refundId, Long paymentId, RefundStatus from, String reason) {
        transactionTemplate.executeWithoutResult(status -> {
            var now = clock.instant();
            if (!refunds.markNeedsManualCheck(refundId, from, now)) {
                metrics.transitionConflict(TransitionOperation.REFUND_WORKER);
                return;
            }
            histories.save(PaymentStatusHistory.ofRefund(paymentId, from, RefundStatus.NEEDS_MANUAL_CHECK, reason,
                    StatusActor.SYSTEM, now));
        });
    }

    private void logClientError(Claim claim, Object result) {
        if (result instanceof PgClient.Unknown unknown && unknown.cause() == UnknownCause.CLIENT_ERROR) {
            // 요청이 PG에 거절됐다. CB에는 세지 않지만 우리 쪽 설정·코드 문제일 수 있어 알린다(INV-8)
            log.error("PG가 환불 요청을 거절했다(CLIENT_ERROR) refundId={}", claim.refundId());
        }
    }

    /**
     * claim한 환불의 호출에 필요한 값과 허가. {@code firstCall}은 claim 전에 PG를 부른 적이 없었는지,
     * {@code attempts}는 이번 claim으로 센 PG 호출 횟수다.
     */
    private record Claim(Long refundId, Long paymentId, RefundStatus status, PgRefundId pgRefundId, BigDecimal amount,
            RefundReason reason, boolean firstCall, int attempts, PgPaymentRef paymentRef, String currency,
            PgPermit permit) {
    }

    private static final class StateConflictException extends RuntimeException {
        StateConflictException() {
            super("결제가 환불할 수 없는 상태다", null, false, false);
        }
    }
}
