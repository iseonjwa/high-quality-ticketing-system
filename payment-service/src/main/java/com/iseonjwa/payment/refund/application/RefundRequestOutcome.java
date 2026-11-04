package com.iseonjwa.payment.refund.application;

/** {@code RefundRequested}를 결제와 대조하고 환불을 만든 결과. 내용 문제는 예외가 아니라 {@link Rejected}로 돌려준다. */
public sealed interface RefundRequestOutcome {
    /** 새 환불 {@code REQUESTED}를 만들었다. */
    record Created(Long refundId) implements RefundRequestOutcome {}

    /** 결제당 환불이 이미 있다(정상 중복). */
    record Duplicate(Long refundId) implements RefundRequestOutcome {}

    /** 검증에 실패했거나 환불을 시작할 수 없는 결제다. {@code message}는 운영자가 읽을 사유다. */
    record Rejected(String message) implements RefundRequestOutcome {}
}
