package com.iseonjwa.payment.payment.domain;

public enum PaymentMethod {
    CARD,
    // 자동결제 이벤트 전용이다. 승인 API는 CARD만 받는다.
    BILLING
}
