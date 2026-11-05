package com.iseonjwa.reservation.lottery.application;

/**
 * 예매 흐름이 추첨에 알리는 유일한 통로(D11·D12, details.md 4절 모듈 경계). 인자는 ID와 값만 받는다.
 * {@code payment.result} 소비자와 예매 만료 스케줄러가 {@code source = LOTTERY} 예매일 때 같은 트랜잭션에서 부른다.
 * 소비자 연결은 ISE-150, 만료 스케줄러 연결은 ISE-149, 구현은 ISE-90이다.
 * 구현은 조건부 UPDATE로 멱등이어야 한다. 같은 결과가 두 번 와도 한 번만 반영한다.
 */
public interface LotteryPaymentResultHandler {

    // 결제 확정. 응모를 PAID로 바꾼다
    void onPaid(Long reservationId);

    /**
     * 결제 거절.
     *
     * @param reasonCode 결제의 거절 사유(contracts.md). {@code NOT_SENT}면 우리 책임 실패라 새 예매로 1회 다시 시도한다(D12)
     */
    void onFailed(Long reservationId, String reasonCode);

    // 선점이 만료됐다. 결제 결과 없이 만료된 경우와, 기한 뒤에 승인이 와 소비자가 만료시킨 경우(환불, D14)다.
    // 결과를 받지 못한 만료의 1회 연장 여부는 만료 스케줄러가 먼저 판단한다(D12·D14)
    void onExpired(Long reservationId);
}
