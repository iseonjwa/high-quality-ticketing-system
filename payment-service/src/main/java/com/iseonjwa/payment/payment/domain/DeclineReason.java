package com.iseonjwa.payment.payment.domain;

/**
 * 우리가 정의한 거절 사유. PG 원본 코드는 저장하지 않고 어댑터가 이 값으로 매핑한다.
 */
public enum DeclineReason {
    // 망취소 뒤 PG에 요청이 닿지 않았음이 확인된 경우
    NOT_SENT,
    // PG 호출 직전에 결제 토큰이 만료된 경우
    TOKEN_EXPIRED,
    // 매핑할 수 없는 PG 사유
    OTHER
    // TODO: PG 거절 사유(한도 초과, 카드 오류 등)는 PgClient 계약을 만드는 ISE-34에서 더한다
}
