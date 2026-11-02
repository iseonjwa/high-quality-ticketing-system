package com.iseonjwa.payment.refund.domain;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * 환불 저장소. 상태 전이는 모두 "현재 상태가 from일 때만" 바꾸는 조건부 UPDATE이고, 반환값은 실제로 바뀌었는지
 * (영향 행이 1인지)다. 호출한 쪽은 true일 때만 이력·Outbox를 쓴다(INV-5).
 *
 * <p>Note: 전이 쿼리는 영속성 컨텍스트를 비운다. 같은 트랜잭션에서 미리 읽어 둔 엔티티는 분리되므로 필요한 값은
 * 전이 앞에 읽어 둔다. 확정(REFUNDED)과 운영자 확인(NEEDS_MANUAL_CHECK)은 {@code next_check_at}을 비워 워커가 더 집지 않게 한다.
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

    /**
     * 처리할 차례가 된 환불(REQUESTED·UNKNOWN, {@code next_check_at <= now}) 하나를 {@code next_check_at} 순으로 잠가서
     * 돌려준다. 다른 트랜잭션이 잠근 행은 건너뛴다. 잠금은 호출한 트랜잭션이 끝날 때까지 유지되므로 트랜잭션 안에서만 부른다.
     */
    Optional<Refund> findDueForUpdate(Instant now);

    /**
     * 환불 처리를 맡는다(임대). 현재 상태가 {@code status}이고 차례({@code next_check_at <= now})일 때만
     * {@code next_check_at}을 임대 만료 시각으로 미루고, 시도 횟수를 1 올리고, {@code pg_requested_at}이 비어 있으면
     * {@code now}로 채운다(이미 채워진 값은 유지한다. 처음 PG를 부른 시각이 "응답이 유실됐을 수 있다"는 표시다).
     */
    boolean claim(Long id, RefundStatus status, Instant leaseUntil, Instant now);

    /** PG가 환불 완료를 확인했다. 환불 거래 식별자와 PG의 환불 시각을 남긴다. */
    boolean markRefunded(Long id, RefundStatus from, String pgRefundRef, Instant refundedAt, Instant now);

    /** REQUESTED 환불의 PG 응답이 불확실해 UNKNOWN으로 옮기고 다음 확인 시각을 정한다. */
    boolean markUnknown(Long id, Instant nextCheckAt, Instant now);

    /** UNKNOWN 환불의 다음 확인 시각만 바꾼다. 다른 경로가 이미 상태를 바꿨으면 아무것도 하지 않고 false다. */
    boolean reschedule(Long id, Instant nextCheckAt, Instant now);

    /** 자동 경로로 끝내지 못해 운영자 확인으로 넘긴다. 이력의 사유는 호출한 쪽이 남긴다. */
    boolean markNeedsManualCheck(Long id, RefundStatus from, Instant now);
}
