package com.iseonjwa.payment.payment.application;

import com.iseonjwa.payment.event.application.PaymentResultPublisher;
import com.iseonjwa.payment.payment.domain.DeclineReason;
import com.iseonjwa.payment.payment.domain.PaymentRepository;
import com.iseonjwa.payment.payment.domain.PaymentStatus;
import com.iseonjwa.payment.payment.domain.PaymentStatusHistory;
import com.iseonjwa.payment.payment.domain.PaymentStatusHistoryRepository;
import com.iseonjwa.payment.payment.domain.StatusActor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;

/**
 * 결제 상태 전이의 공통 규칙(domain-and-states.md 4절)을 한곳에서 지킨다.
 * 허용된 전이인지 확인하고, 조건부 UPDATE의 영향 행이 1일 때만 이력을 쓴다.
 * 승인·거절로 바뀐 전이는 같은 트랜잭션에서 Outbox에 결과 이벤트를 쓴다(INV-5).
 * 반환값 false는 다른 경로가 먼저 상태를 바꿨다는 뜻이다. 호출한 쪽이 현재 상태를 다시 읽어 응답한다.
 *
 * <p>Note: 각 메서드는 호출한 유스케이스의 트랜잭션(TX1·TX2 등)에 참여한다. 이력과 상태가 한 트랜잭션에서
 * 함께 커밋되거나 롤백된다(INV-5).
 */
@Service
public class PaymentTransitionService {
    private final PaymentRepository payments;
    private final PaymentStatusHistoryRepository histories;
    private final PaymentResultPublisher resultPublisher;
    private final Clock clock;

    public PaymentTransitionService(PaymentRepository payments, PaymentStatusHistoryRepository histories,
            PaymentResultPublisher resultPublisher, @Qualifier("commonAuditingClock") Clock clock) {
        this.payments = payments;
        this.histories = histories;
        this.resultPublisher = resultPublisher;
        this.clock = clock;
    }

    @Transactional
    // Note: 승인 금액이 결제 금액과 같은지는 호출한 승인 흐름(ISE-35)이 먼저 확인하고, 다르면 markAmountMismatch를 쓴다.
    // 이 메서드에 다른 금액이 들어오면 chk_payment_approved가 마지막으로 막는다(예외, 트랜잭션 롤백).
    public boolean approve(Long paymentId, PaymentStatus from, String pgTxId, BigDecimal pgApprovedAmount,
            StatusActor actor) {
        from.requireTransitionTo(PaymentStatus.APPROVED);
        var now = clock.instant();
        var changed = payments.markApproved(paymentId, from, pgTxId, pgApprovedAmount, now, now);
        if (!recordIfChanged(changed, paymentId, from, PaymentStatus.APPROVED, null, actor, now)) return false;
        resultPublisher.publishApproved(payments.findById(paymentId).orElseThrow());
        return true;
    }

    @Transactional
    public boolean decline(Long paymentId, PaymentStatus from, DeclineReason reason, StatusActor actor) {
        from.requireTransitionTo(PaymentStatus.DECLINED);
        var now = clock.instant();
        var changed = payments.markDeclined(paymentId, from, reason, now);
        if (!recordIfChanged(changed, paymentId, from, PaymentStatus.DECLINED, reason.name(), actor, now)) return false;
        resultPublisher.publishDeclined(payments.findById(paymentId).orElseThrow());
        return true;
    }

    @Transactional
    public boolean markUnknown(Long paymentId, PaymentStatus from, Instant nextCheckAt, String reason,
            StatusActor actor) {
        from.requireTransitionTo(PaymentStatus.UNKNOWN);
        var now = clock.instant();
        var changed = payments.markUnknown(paymentId, from, nextCheckAt, now);
        return recordIfChanged(changed, paymentId, from, PaymentStatus.UNKNOWN, reason, actor, now);
    }

    // PG가 승인했지만 금액이 결제와 다르다. PG 거래 정보를 남기고 운영자 확인으로 넘긴다.
    @Transactional
    public boolean markAmountMismatch(Long paymentId, PaymentStatus from, String pgTxId, BigDecimal pgApprovedAmount,
            StatusActor actor) {
        from.requireTransitionTo(PaymentStatus.NEEDS_MANUAL_CHECK);
        var now = clock.instant();
        var changed = payments.markNeedsManualCheck(paymentId, from, pgTxId, pgApprovedAmount, now, now);
        return recordIfChanged(changed, paymentId, from, PaymentStatus.NEEDS_MANUAL_CHECK, "PG_AMOUNT_MISMATCH", actor,
                now);
    }

    // 재조회를 소진해도 PG 결과를 알 수 없다. 승인된 적이 없으므로 PG 정보와 승인 시각을 남기지 않는다.
    @Transactional
    public boolean markUnresolved(Long paymentId, PaymentStatus from, StatusActor actor) {
        from.requireTransitionTo(PaymentStatus.NEEDS_MANUAL_CHECK);
        var now = clock.instant();
        var changed = payments.markNeedsManualCheck(paymentId, from, null, null, null, now);
        return recordIfChanged(changed, paymentId, from, PaymentStatus.NEEDS_MANUAL_CHECK, "PG_UNRESOLVED", actor, now);
    }

    private boolean recordIfChanged(boolean changed, Long paymentId, PaymentStatus from, PaymentStatus to,
            String reason, StatusActor actor, Instant now) {
        if (!changed) {
            // TODO: payment_transition_conflict_total 지표를 올린다(ISE-41)
            return false;
        }
        histories.save(PaymentStatusHistory.of(paymentId, from, to, reason, actor, now));
        return true;
    }
}
