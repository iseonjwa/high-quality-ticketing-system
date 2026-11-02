package com.iseonjwa.payment.refund.domain;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * 환불 저장소. 상태 전이 메서드는 환불 워커(ISE-137)가 필요할 때 더한다.
 */
public interface RefundRepository {
    /**
     * 결제가 {@code APPROVED}이고 예매 ID가 맞을 때만 {@code REQUESTED} 환불을 만든다. 금액은 호출한 쪽이 넘기지 않고
     * 결제의 {@code pg_approved_amount}를 복사한다(INV-2·INV-3). {@code next_check_at}은 {@code now}다.
     *
     * <p>결제당 환불이 이미 있으면 {@code ON CONFLICT (payment_id) DO NOTHING}이라 만들지 않는다.
     *
     * @return 만들었으면 1, 만들지 않았으면 0. 0이면 환불 행이 이미 있는지(중복) 결제 조건이 어긋났는지를 다시 읽어 가린다
     */
    int createForApprovedPayment(Long paymentId, Long reservationId, UUID pgRefundId, RefundReason reason,
            RefundSource source, UUID sourceEventId, Instant now);

    Optional<Refund> findByPaymentId(Long paymentId);
}
