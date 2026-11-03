package com.iseonjwa.payment.refund.application;

import com.iseonjwa.payment.payment.domain.PaymentStatusHistory;
import com.iseonjwa.payment.payment.domain.PaymentStatusHistoryRepository;
import com.iseonjwa.payment.payment.domain.StatusActor;
import com.iseonjwa.payment.refund.domain.RefundRepository;
import com.iseonjwa.payment.refund.domain.RefundStatus;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

/**
 * 운영자 확인({@code NEEDS_MANUAL_CHECK}) 환불을 {@code REQUESTED}로 되돌리는 전이(Ops {@code refund retry}). PG는 부르지
 * 않고, 이어받는 일은 환불 워커가 한다. 호출한 쪽의 트랜잭션에 참여한다(환불 → 이력, 조건부 UPDATE가 1행일 때만).
 */
@Service
public class RefundRetryService {
    public static final String HISTORY_REASON = "OPS_RETRY";

    private final RefundRepository refunds;
    private final PaymentStatusHistoryRepository histories;
    private final Clock clock;

    public RefundRetryService(RefundRepository refunds, PaymentStatusHistoryRepository histories,
            @Qualifier("commonAuditingClock") Clock clock) {
        this.refunds = refunds;
        this.histories = histories;
        this.clock = clock;
    }

    /** 환불 {@code NEEDS_MANUAL_CHECK} → {@code REQUESTED}. 환불이 이미 다른 상태면 아무것도 쓰지 않고 false다. */
    @Transactional
    public boolean requeue(Long refundId, Long paymentId, StatusActor actor) {
        var now = clock.instant();
        if (!refunds.requeue(refundId, RefundStatus.NEEDS_MANUAL_CHECK, now)) return false;
        histories.save(PaymentStatusHistory.ofRefund(paymentId, RefundStatus.NEEDS_MANUAL_CHECK, RefundStatus.REQUESTED,
                HISTORY_REASON, actor, now));
        return true;
    }
}
