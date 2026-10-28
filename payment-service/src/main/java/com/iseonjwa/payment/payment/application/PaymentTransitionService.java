package com.iseonjwa.payment.payment.application;

import com.iseonjwa.payment.payment.domain.DeclineReason;
import com.iseonjwa.payment.payment.domain.PaymentRepository;
import com.iseonjwa.payment.payment.domain.PaymentStatus;
import com.iseonjwa.payment.payment.domain.PaymentStatusHistory;
import com.iseonjwa.payment.payment.domain.PaymentStatusHistoryRepository;
import com.iseonjwa.payment.payment.domain.StatusActor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;

/**
 * 결제 상태 전이의 공통 규칙(domain-and-states.md 4절)을 한곳에서 지킨다.
 * 허용된 전이인지 확인하고, 조건부 UPDATE의 영향 행이 1일 때만 이력을 쓴다.
 * 반환값 false는 다른 경로가 먼저 상태를 바꿨다는 뜻이다. 호출한 쪽이 현재 상태를 다시 읽어 응답한다.
 *
 * <p>Note: 각 메서드는 호출한 유스케이스의 트랜잭션(TX1·TX2 등)에 참여한다. 이력과 상태가 한 트랜잭션에서
 * 함께 커밋되거나 롤백된다(INV-5).
 */
@Service
public class PaymentTransitionService {
    private final PaymentRepository payments;
    private final PaymentStatusHistoryRepository histories;
    private final Clock clock;

    public PaymentTransitionService(PaymentRepository payments, PaymentStatusHistoryRepository histories,
            Clock clock) {
        this.payments = payments;
        this.histories = histories;
        this.clock = clock;
    }

    @Transactional
    public boolean approve(Long paymentId, PaymentStatus from, String pgTxId, BigDecimal pgApprovedAmount,
            StatusActor actor) {
        from.requireTransitionTo(PaymentStatus.APPROVED);
        var now = clock.instant();
        var changed = payments.markApproved(paymentId, from, pgTxId, pgApprovedAmount, now, now);
        // TODO: 영향 행이 1이면 PaymentApproved를 Outbox에 쓴다(ISE-24)
        return recordIfChanged(changed, paymentId, from, PaymentStatus.APPROVED, null, actor, now);
    }

    @Transactional
    public boolean decline(Long paymentId, PaymentStatus from, DeclineReason reason, StatusActor actor) {
        from.requireTransitionTo(PaymentStatus.DECLINED);
        var now = clock.instant();
        var changed = payments.markDeclined(paymentId, from, reason, now);
        // TODO: 영향 행이 1이면 PaymentDeclined를 Outbox에 쓴다(ISE-24)
        return recordIfChanged(changed, paymentId, from, PaymentStatus.DECLINED, reason.name(), actor, now);
    }

    @Transactional
    public boolean markUnknown(Long paymentId, PaymentStatus from, Instant nextCheckAt, String reason,
            StatusActor actor) {
        from.requireTransitionTo(PaymentStatus.UNKNOWN);
        var now = clock.instant();
        var changed = payments.markUnknown(paymentId, from, nextCheckAt, now);
        return recordIfChanged(changed, paymentId, from, PaymentStatus.UNKNOWN, reason, actor, now);
    }

    @Transactional
    public boolean markNeedsManualCheck(Long paymentId, PaymentStatus from, String pgTxId,
            BigDecimal pgApprovedAmount, String reason, StatusActor actor) {
        from.requireTransitionTo(PaymentStatus.NEEDS_MANUAL_CHECK);
        var now = clock.instant();
        // PG 승인 정보가 있는 불일치 전이만 승인 시각을 남긴다. 재조회 소진은 승인된 적이 없다
        var approvedAt = pgTxId == null ? null : now;
        var changed = payments.markNeedsManualCheck(paymentId, from, pgTxId, pgApprovedAmount, approvedAt, now);
        return recordIfChanged(changed, paymentId, from, PaymentStatus.NEEDS_MANUAL_CHECK, reason, actor, now);
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
