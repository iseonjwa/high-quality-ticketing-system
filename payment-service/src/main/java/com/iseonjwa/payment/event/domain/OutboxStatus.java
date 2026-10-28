package com.iseonjwa.payment.event.domain;

public enum OutboxStatus {
    PENDING,
    SENT,
    // 영구 오류로 발행을 포기한 건. 사람이 확인한다(ISE-40에서 전이를 구현한다)
    FAILED
}
