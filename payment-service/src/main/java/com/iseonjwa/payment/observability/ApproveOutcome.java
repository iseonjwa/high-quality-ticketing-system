package com.iseonjwa.payment.observability;

/** 승인 API 호출 하나의 결과. {@code payment.approve} 타이머의 {@code outcome} 라벨 값이다. */
public enum ApproveOutcome {
    /** PG가 승인했다(금액 불일치로 운영자 확인에 들어가도 PG 결과는 승인이라 여기에 센다) */
    APPROVED,
    /** PG가 거절했거나 PG를 부르기 전에 토큰이 만료돼 거절로 닫았다 */
    DECLINED,
    /** PG 결과를 알 수 없어 재확인에 넘겼다 */
    UNKNOWN,
    /** 같은 키의 재요청이나 다른 키 연결로 기존 결제를 돌려줬다 */
    LINKED,
    /** 요청 검증·토큰·금액·충돌 같은 4xx로 거절했다 */
    REJECTED,
    /** PG 승인 허가를 얻지 못해 503으로 거절했다(Circuit Breaker OPEN 또는 Bulkhead 포화) */
    UNAVAILABLE,
    /** 그 밖의 예외(5xx) */
    ERROR
}
