package com.iseonjwa.payment.payment.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

/**
 * 결제 저장소. 상태 전이는 모두 "현재 상태가 from일 때만" 바꾸는 조건부 UPDATE이고,
 * 반환값은 실제로 바뀌었는지(영향 행이 1인지)다. 호출한 쪽은 true일 때만 이력·이벤트를 쓴다(INV-5).
 *
 * <p>Note: 전이 쿼리는 영속성 컨텍스트를 비운다. 같은 트랜잭션에서 미리 읽어 둔 엔티티는 분리되므로,
 * 전이 뒤에 값이 필요하면 다시 읽고 엔티티를 바꿔 저장하는 일은 전이 앞에 끝낸다.
 */
public interface PaymentRepository {
    Payment save(Payment payment);

    // 내부 흐름(승인·전이)과 ADMIN 조회만 쓴다. 사용자 요청 경로는 findByIdAndUserId를 쓴다(0021)
    Optional<Payment> findById(Long id);

    // 소유자 조건을 쿼리에 넣어, 타인의 결제는 메모리에 올라오지 않는다
    Optional<Payment> findByIdAndUserId(Long id, Long userId);

    // 타인 결제 접근을 WARN으로 남기기 위한 확인 전용이다. 결과로 응답을 나누지 않는다(PaymentQueryService)
    boolean existsById(Long id);

    Optional<Payment> findByReservationId(Long reservationId);

    /**
     * 재확인할 차례가 된 결제(REQUESTED·UNKNOWN, {@code next_check_at <= now}) 하나를 {@code next_check_at} 순으로 잠가서 돌려준다.
     * 다른 트랜잭션이 잠근 행은 건너뛴다. 잠금은 호출한 트랜잭션이 끝날 때까지 유지되므로 트랜잭션 안에서만 부른다.
     */
    Optional<Payment> findDueForRecheckForUpdate(Instant now);

    /**
     * 재확인을 맡는다(임대). 현재 상태가 {@code status}일 때만 {@code next_check_at}을 임대 만료 시각으로 미루고
     * 시도 횟수를 1 올린다. 임대 동안 다른 워커가 같은 결제를 집지 않고, 워커가 죽어도 임대가 끝나면 다시 대상이 된다.
     */
    boolean claimRecheck(Long id, PaymentStatus status, Instant leaseUntil, Instant now);

    // UNKNOWN 결제의 다음 재확인 시각만 바꾼다. 다른 경로가 이미 상태를 바꿨으면 아무것도 하지 않고 false다.
    boolean rescheduleCheck(Long id, Instant nextCheckAt, Instant now);

    boolean markApproved(Long id, PaymentStatus from, String pgTxId, BigDecimal pgApprovedAmount, Instant approvedAt,
            Instant now);

    boolean markDeclined(Long id, PaymentStatus from, DeclineReason reason, Instant now);

    // PG 응답이 불확실해 확정하지 못한 상태. 재확인 스케줄러가 nextCheckAt에 다시 조회한다.
    boolean markUnknown(Long id, PaymentStatus from, Instant nextCheckAt, Instant now);

    // 승인 금액 불일치처럼 PG 거래 정보를 남겨야 하면 값을 넘기고, 재조회 소진처럼 없으면 null이다.
    boolean markNeedsManualCheck(Long id, PaymentStatus from, String pgTxId, BigDecimal pgApprovedAmount,
            Instant approvedAt, Instant now);
}
