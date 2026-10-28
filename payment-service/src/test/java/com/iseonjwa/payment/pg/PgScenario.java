package com.iseonjwa.payment.pg;

/**
 * 계약 테스트가 PG 쪽 상황을 만드는 방법. 어댑터마다 구현한다(Mock은 장애 주입, 토스는 HTTP 스텁).
 * 각 설정은 바로 다음 호출 한 번에만 적용된다.
 */
public interface PgScenario {
    void nextApprovalIsDeclined();

    void nextApprovalIsSlow();

    void nextApprovalResponseIsLost();

    void nextApprovalIsProcessing();

    void nextApprovalIsBadRequest();

    void nextApprovalOmitsAmountAndCurrency();

    void nextApprovalUsesDifferentAmount();

    void nextRefundIsRejected();

    void nextRefundResponseIsLost();

    void nextRefundIsProcessing();
}
