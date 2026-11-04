package com.iseonjwa.payment.payment.domain;

/**
 * 우리가 정의한 거절 사유. PG 원본 코드는 저장하지 않고 어댑터가 이 값으로 매핑한다.
 */
public enum DeclineReason {
    // PG가 승인하지 않았음이 확인된 경우(망취소된 주문의 승인 거부 등)
    NOT_SENT,
    // PG 호출 직전에 결제 토큰이 만료된 경우
    TOKEN_EXPIRED,
    // PG가 카드를 거절함
    CARD_DECLINED,
    // 한도 부족
    INSUFFICIENT_LIMIT,
    // 자동결제할 활성 빌링키가 없거나 PG가 빌링키를 거절함(추첨 D4·D12: 당첨자 책임이라 승계)
    BILLING_KEY_MISSING,
    // 매핑할 수 없는 PG 사유
    OTHER
}
