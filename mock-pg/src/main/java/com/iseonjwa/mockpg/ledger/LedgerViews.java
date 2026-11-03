package com.iseonjwa.mockpg.ledger;

import java.math.BigDecimal;
import java.util.List;

/**
 * 원장 조회 API가 돌려주는 읽기 전용 값(mock-pg.md 9절). 호출 시점의 복사본이라 원장의 이후 변경에 영향받지 않는다.
 */
public final class LedgerViews {
    private LedgerViews() {
    }

    /** 승인 {@code Idempotency-Key} 헤더의 기록. 마지막 값과 서로 다른 값의 수다. 헤더를 한 번도 받지 않았으면 {@code last}가 null이다. */
    public record IdempotencyKeys(String last, int distinctCount) {
    }

    /**
     * 주문 하나의 원장 기록.
     *
     * @param status 저장된 상태: {@code IN_FLIGHT}, {@code APPROVED}, {@code DECLINED}, {@code CANCELED}.
     *               호출만 있고 저장된 상태가 없으면(장애 주입 500·202만 받은 승인 등) {@code NONE}이다
     * @param pgTxId 승인된 주문만 있다. 그 밖에는 null이다
     */
    public record OrderView(String orderId, String status, String pgTxId, BigDecimal amount, String currency,
            long approveCalls, long lookupCalls, long cancelCalls, BigDecimal refundedTotal,
            IdempotencyKeys idempotencyKeys) {
    }

    public record RefundView(String refundId, String refundTxId, BigDecimal amount, String currency, long refundCalls,
            long refundLookupCalls, IdempotencyKeys idempotencyKeys) {
    }

    /** 한 결제의 환불 기록. 저장된 환불만 담는다(거절은 저장하지 않는다). */
    public record PaymentRefundsView(List<RefundView> refunds, int refundCount, BigDecimal refundedTotal) {
    }

    /**
     * 원장 전체 요약. 건수는 호출 시점에 맵을 훑어 계산한다(전역 계수기를 두지 않는다).
     *
     * @param orderCount 저장된 상태가 있는 주문 수. 호출만 있고 상태가 없는 주문은 세지 않는다
     */
    public record Summary(long approvedCount, long declinedCount, long canceledCount, long refundCount,
            BigDecimal approvedTotal, BigDecimal refundedTotal, long inFlightCount, long orderCount) {
    }
}
