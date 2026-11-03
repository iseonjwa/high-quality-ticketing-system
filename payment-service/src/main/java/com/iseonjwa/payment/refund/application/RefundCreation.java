package com.iseonjwa.payment.refund.application;

/** 검증을 통과한 환불 요청으로 환불 행을 만든 결과. */
public sealed interface RefundCreation {
    /** 새 환불 {@code REQUESTED}를 만들고 이력까지 썼다. */
    record Created(Long refundId) implements RefundCreation {}

    /** 결제당 환불이 이미 있다(정상 중복). */
    record Duplicate(Long refundId) implements RefundCreation {}

    /** 결제가 환불을 시작할 수 있는 상태가 아니고(이벤트 경로는 {@code APPROVED}, 금액 불일치 경로는 {@code NEEDS_MANUAL_CHECK}) 환불 행도 없다. */
    record NotApproved(String message) implements RefundCreation {}
}
