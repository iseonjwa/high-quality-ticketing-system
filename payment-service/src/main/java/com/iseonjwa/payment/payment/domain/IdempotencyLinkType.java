package com.iseonjwa.payment.payment.domain;

public enum IdempotencyLinkType {
    // TX1에서 결제를 만든 키. 응답이 저장될 수 있다
    ORIGIN,
    // 같은 예매의 기존 결제에 연결된 새 키(0019). 응답은 저장하지 않고 연결 결제의 현재 상태로 답한다
    ALIAS
}
