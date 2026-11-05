package com.iseonjwa.reservation.lottery.application;

/**
 * 예매 흐름이 {@code source = LOTTERY} 예매의 결과를 같은 트랜잭션에서 추첨에 알리는 유일한 통로로, 구현은 조건부 UPDATE로 멱등이어야 한다(D11·D12).
 */
public interface LotteryPaymentResultHandler {

    /**
     * 결제 확정으로 응모를 PAID로 바꾼다.
     */
    void onPaid(Long reservationId);

    /**
     * 결제 거절을 응모에 반영한다.
     *
     * @param reasonCode 결제의 거절 사유(contracts.md). {@code NOT_SENT}면 우리 책임 실패라 새 예매로 1회 다시 시도한다(D12)
     */
    void onFailed(Long reservationId, String reasonCode);

    /**
     * 결제 결과 없는 만료와 기한 뒤 승인으로 인한 만료를 반영하며, 1회 연장 여부는 만료 스케줄러가 먼저 판단한다(D12·D14).
     */
    void onExpired(Long reservationId);
}
