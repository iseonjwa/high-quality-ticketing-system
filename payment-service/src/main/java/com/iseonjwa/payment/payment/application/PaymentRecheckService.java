package com.iseonjwa.payment.payment.application;

import com.iseonjwa.payment.payment.domain.DeclineReason;
import com.iseonjwa.payment.payment.domain.PaymentRepository;
import com.iseonjwa.payment.payment.domain.PaymentStatus;
import com.iseonjwa.payment.payment.domain.StatusActor;
import com.iseonjwa.payment.pg.application.PgClient;
import com.iseonjwa.payment.pg.application.PgOrderId;
import com.iseonjwa.payment.pg.application.PgPaymentRef;
import com.iseonjwa.payment.pg.application.PgPermit;
import com.iseonjwa.payment.pg.application.PgRecoveryGate;
import com.iseonjwa.payment.pg.application.UnknownCause;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 결제 재확인 한 건의 처리(events-and-reliability.md 6절). 재확인할 차례가 된 {@code REQUESTED}·{@code UNKNOWN} 결제
 * 하나를 맡아(claim) PG에 조회하고 결과로 확정한다. 순서는 후보 잠금 → PG 허가 → 임대 → (트랜잭션 밖) PG 조회 → 결과 반영이다.
 *
 * <p>Note: 허가를 claim보다 먼저 얻는다. 허가를 못 얻은 주기는 아무것도 바꾸지 않아 시도 횟수를 세지 않고, 그래서 PG 전체 장애로
 * 서킷이 열린 동안에는 횟수가 줄지 않는다. PG 조회는 트랜잭션 밖이라 DB 연결을 잡지 않는다.
 */
@Service
public class PaymentRecheckService {
    // claim한 워커가 결과를 반영하기 전에 죽어도 이 시간이 지나면 다시 대상이 된다(7절). 조회 시한보다 충분히 길게 둔다
    private static final Duration LEASE = Duration.ofSeconds(30);
    private static final String PERMIT_DENIED = "RECOVERY_PERMIT_DENIED";

    private static final Logger log = LoggerFactory.getLogger(PaymentRecheckService.class);

    private final PaymentRepository payments;
    private final PaymentTransitionService transitions;
    private final PgRecoveryGate recoveryGate;
    private final PgClient pg;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    public PaymentRecheckService(PaymentRepository payments, PaymentTransitionService transitions,
            PgRecoveryGate recoveryGate, PgClient pg, TransactionTemplate transactionTemplate,
            @Qualifier("commonAuditingClock") Clock clock) {
        this.payments = payments;
        this.transitions = transitions;
        this.recoveryGate = recoveryGate;
        this.pg = pg;
        this.transactionTemplate = transactionTemplate;
        this.clock = clock;
    }

    /**
     * 재확인할 결제 하나를 처리한다.
     *
     * @return 처리했으면 true. 할 일이 없거나 PG 복구 허가를 못 얻었으면 false다(호출한 쪽이 이번 주기를 끝낸다)
     */
    public boolean recheckNext() {
        var claim = claimNext();
        if (claim.isEmpty()) return false;
        try (var ignored = claim.get().permit()) {
            resolve(claim.get());
        } catch (RuntimeException | Error exception) {
            // 주기 로그에는 결제 id가 없어서, 같은 건이 반복 실패해도 어느 건인지 알 수 없다
            log.error("결제 재확인 처리 실패 paymentId={}", claim.get().paymentId(), exception);
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
            // 허가를 돌려주고(호출 결과로 세지 않는다), 정상 claim이면 recheckNext가 받아 쓴다
            if (!claimed) closeIfHeld(heldPermit);
        }
    }

    private Optional<Claim> claimInTransaction(AtomicReference<PgPermit> heldPermit) {
        var now = clock.instant();
        var candidate = payments.findDueForRecheckForUpdate(now);
        if (candidate.isEmpty()) return Optional.empty();

        var permit = recoveryGate.tryAcquire();
        if (permit.isEmpty()) return Optional.empty();
        heldPermit.set(permit.get());

        // 임대하면 엔티티가 영속성 컨텍스트에서 분리되므로 필요한 값을 먼저 읽는다
        var payment = candidate.get();
        var claim = new Claim(payment.getId(), payment.getStatus(), new PgOrderId(payment.getPgOrderId()),
                payment.getAmount(), payment.getCurrency(), payment.getUnknownRetryCount() + 1, permit.get());
        if (!payments.claimRecheck(payment.getId(), payment.getStatus(), now.plus(LEASE), now)) {
            return Optional.empty();
        }
        return Optional.of(claim);
    }

    private static void closeIfHeld(AtomicReference<PgPermit> heldPermit) {
        var held = heldPermit.get();
        if (held != null) held.close();
    }

    private void resolve(Claim claim) {
        var permit = claim.permit();
        permit.callStarted();
        var result = findPayment(claim);
        permit.settle(result);
        logClientError(claim, result);
        switch (result) {
            case PgClient.Approved approved -> confirm(claim, approved.paymentRef(), approved.amount(),
                    approved.approvedAt(), approved.matches(claim.amount(), claim.currency()));
            case PgClient.Declined declined ->
                    transitions.decline(claim.paymentId(), claim.status(), declined.reason(), StatusActor.SYSTEM);
            case PgClient.NotFound notFound -> preventLateApproval(claim);
            case PgClient.Unknown unknown -> handleUnknown(claim, unknown.cause().name());
        }
    }

    // 포트는 결과를 예외가 아닌 타입으로 돌려주지만, 예외가 나오면 결과를 모르는 것(Unknown)으로 다룬다
    private PgClient.PaymentLookupResult findPayment(Claim claim) {
        try {
            var result = pg.findPayment(claim.pgOrderId());
            if (result != null) return result;
            log.error("PG 결제 조회 결과가 null이다 paymentId={}", claim.paymentId());
            return new PgClient.Unknown(UnknownCause.PG_ERROR);
        } catch (RuntimeException exception) {
            log.error("PG 결제 조회 중 예외 paymentId={}", claim.paymentId(), exception);
            return new PgClient.Unknown(UnknownCause.PG_ERROR);
        }
    }

    // 조회에서 PG가 주문을 모른다고 했다. 이후에도 승인되지 않게 막거나, 이미 승인됐음을 확인한다
    private void preventLateApproval(Claim claim) {
        var permit = recoveryGate.tryAcquire();
        if (permit.isEmpty()) {
            handleUnknown(claim, PERMIT_DENIED);
            return;
        }
        try (var held = permit.get()) {
            held.callStarted();
            var result = preventLate(claim);
            held.settle(result);
            logClientError(claim, result);
            switch (result) {
                case PgClient.Prevented prevented ->
                        transitions.decline(claim.paymentId(), claim.status(), DeclineReason.NOT_SENT, StatusActor.SYSTEM);
                case PgClient.AlreadyApproved approved -> confirm(claim, approved.paymentRef(), approved.amount(),
                        approved.approvedAt(), approved.matches(claim.amount(), claim.currency()));
                case PgClient.Unknown unknown -> handleUnknown(claim, unknown.cause().name());
            }
        }
    }

    private PgClient.LateApprovalResult preventLate(Claim claim) {
        try {
            var result = pg.preventLateApproval(claim.pgOrderId());
            if (result != null) return result;
            log.error("PG 늦은 승인 방지 결과가 null이다 paymentId={}", claim.paymentId());
            return new PgClient.Unknown(UnknownCause.PG_ERROR);
        } catch (RuntimeException exception) {
            log.error("PG 늦은 승인 방지 중 예외 paymentId={}", claim.paymentId(), exception);
            return new PgClient.Unknown(UnknownCause.PG_ERROR);
        }
    }

    // PG가 승인했다. 금액·통화가 결제와 같으면 승인, 다르면 운영자 확인으로 넘긴다(INV-3). 0행이면 다른 경로가 먼저 바꾼 것이다
    private void confirm(Claim claim, PgPaymentRef paymentRef, BigDecimal pgAmount, Instant approvedAt,
            boolean matches) {
        if (matches) {
            transitions.approve(claim.paymentId(), claim.status(), paymentRef.value(), pgAmount, approvedAt,
                    StatusActor.SYSTEM);
            return;
        }
        // TODO: 즉시 알림 지표는 ISE-41에서 붙인다. 그 전까지는 로그가 유일한 신호다(INV-3, N16)
        log.error("event=PG_AMOUNT_MISMATCH paymentId={} expectedAmount={} pgAmount={} pgTxId={}", claim.paymentId(),
                claim.amount(), pgAmount, paymentRef.value());
        transitions.markAmountMismatch(claim.paymentId(), claim.status(), paymentRef.value(), pgAmount, approvedAt,
                StatusActor.SYSTEM);
    }

    // 결과를 모른다. 정해진 간격으로 다시 조회하고, 소진하면 운영자 확인으로 넘긴다(SA 9-4)
    private void handleUnknown(Claim claim, String cause) {
        var delay = RecheckSchedule.delayAfter(claim.attempts());
        if (delay.isEmpty()) {
            // TODO: 즉시 알림 지표는 ISE-41에서 붙인다
            log.error("event=PG_UNRESOLVED paymentId={} attempts={} lastCause={}", claim.paymentId(), claim.attempts(),
                    cause);
            transitions.markUnresolved(claim.paymentId(), claim.status(), StatusActor.SYSTEM);
            return;
        }
        var nextCheckAt = clock.instant().plus(delay.get());
        if (claim.status() == PaymentStatus.REQUESTED) {
            transitions.markUnknown(claim.paymentId(), PaymentStatus.REQUESTED, nextCheckAt, cause, StatusActor.SYSTEM);
        } else {
            payments.rescheduleCheck(claim.paymentId(), nextCheckAt, clock.instant());
        }
    }

    private void logClientError(Claim claim, Object result) {
        if (result instanceof PgClient.Unknown unknown && unknown.cause() == UnknownCause.CLIENT_ERROR) {
            // 요청이 PG에 거절됐다. CB에는 세지 않지만 우리 쪽 설정·코드 문제일 수 있어 알린다(INV-8)
            log.error("PG가 재확인 요청을 거절했다(CLIENT_ERROR) paymentId={}", claim.paymentId());
        }
    }

    /** claim한 결제의 호출에 필요한 값과 허가. {@code attempts}는 이번 claim으로 센 PG 호출 횟수다. */
    private record Claim(Long paymentId, PaymentStatus status, PgOrderId pgOrderId, BigDecimal amount, String currency,
            int attempts, PgPermit permit) {
    }
}
