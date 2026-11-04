package com.iseonjwa.payment.refund.application;

import com.iseonjwa.payment.refund.domain.RefundReason;

/**
 * {@code RefundRequested}를 결제와 대조한 결과(events-and-reliability.md 4절 표). 예외가 아닌 값이라, 바깥 트랜잭션 안에서
 * 내용 문제를 예외로 던지지 않고 다룰 수 있다.
 */
public sealed interface RefundRequestVerification {
    /** 결제와 맞고 이벤트로 받을 수 있는 사유다. */
    record Valid(RefundReason reason) implements RefundRequestVerification {}

    /** 결제가 없거나 값이 다르다. {@code message}는 운영자가 읽을 사유다. */
    record Invalid(String message) implements RefundRequestVerification {}
}
