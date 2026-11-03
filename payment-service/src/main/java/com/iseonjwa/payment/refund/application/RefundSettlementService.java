package com.iseonjwa.payment.refund.application;

import com.iseonjwa.payment.payment.application.PaymentTransitionService;
import com.iseonjwa.payment.payment.domain.PaymentStatus;
import com.iseonjwa.payment.payment.domain.PaymentStatusHistory;
import com.iseonjwa.payment.payment.domain.PaymentStatusHistoryRepository;
import com.iseonjwa.payment.payment.domain.StatusActor;
import com.iseonjwa.payment.refund.domain.RefundReason;
import com.iseonjwa.payment.refund.domain.RefundRepository;
import com.iseonjwa.payment.refund.domain.RefundStatus;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

/**
 * PG가 환불을 확인했을 때 환불과 결제를 함께 끝내는 전이. 환불 워커와 Ops {@code refund resolve}가 같은 규칙을 쓰도록
 * 한곳에 둔다. 호출한 쪽의 트랜잭션에 참여한다(환불 → 이력 → 결제 순, 조건부 UPDATE가 1행일 때만).
 *
 * <p>Note: 충돌 지표와 롤백 방식은 호출한 쪽의 일이다. 워커는 결과를 지표로 세고 예외로, Ops는 거부로 바꿔 던진다.
 * {@link Outcome#PAYMENT_NOT_REFUNDABLE}이면 환불 전이가 이미 쓰였으므로 호출한 쪽이 반드시 트랜잭션을 롤백해야 한다.
 * 환불만 {@code REFUNDED}로 남으면 돈은 돌아갔는데 결제는 환불되지 않은 모순이 된다.
 */
@Service
public class RefundSettlementService {
    private final RefundRepository refunds;
    private final PaymentStatusHistoryRepository histories;
    private final PaymentTransitionService transitions;
    private final Clock clock;

    public RefundSettlementService(RefundRepository refunds, PaymentStatusHistoryRepository histories,
            PaymentTransitionService transitions, @Qualifier("commonAuditingClock") Clock clock) {
        this.refunds = refunds;
        this.histories = histories;
        this.transitions = transitions;
        this.clock = clock;
    }

    /** 환불 완료가 결제를 옮기기 전의 상태. 금액 불일치 환불(Ops 명령이 시작)만 운영자 확인 상태의 결제를 환불한다. */
    public static PaymentStatus paymentStatusBeforeRefund(RefundReason reason) {
        return reason == RefundReason.PG_AMOUNT_MISMATCH ? PaymentStatus.NEEDS_MANUAL_CHECK : PaymentStatus.APPROVED;
    }

    /** 환불 {@code from} → {@code REFUNDED}, 결제 → {@code REFUNDED}(+ {@code PaymentRefunded}). */
    @Transactional
    public Outcome complete(Long refundId, Long paymentId, RefundStatus from, RefundReason reason, String pgRefundRef,
            Instant refundedAt, StatusActor actor) {
        var now = clock.instant();
        if (!refunds.markRefunded(refundId, from, pgRefundRef, refundedAt, now)) return Outcome.REFUND_CHANGED;
        histories.save(PaymentStatusHistory.ofRefund(paymentId, from, RefundStatus.REFUNDED, null, actor, now));
        if (!transitions.markRefunded(paymentId, paymentStatusBeforeRefund(reason), refundedAt, actor)) {
            return Outcome.PAYMENT_NOT_REFUNDABLE;
        }
        return Outcome.COMPLETED;
    }

    /** 환불 {@code from} → {@code FAILED}. 결제는 그대로 두고 이벤트도 내지 않는다. */
    @Transactional
    public boolean closeAsFailed(Long refundId, Long paymentId, RefundStatus from, String reason, StatusActor actor) {
        var now = clock.instant();
        if (!refunds.markFailed(refundId, from, now)) return false;
        histories.save(PaymentStatusHistory.ofRefund(paymentId, from, RefundStatus.FAILED, reason, actor, now));
        return true;
    }

    public enum Outcome {
        COMPLETED,
        /** 환불이 이미 다른 경로로 바뀌었다(0행). 더 쓴 것이 없다 */
        REFUND_CHANGED,
        /** 결제가 환불할 수 없는 상태다(0행). 환불 전이는 이미 썼으니 롤백해야 한다 */
        PAYMENT_NOT_REFUNDABLE
    }
}
