package com.iseonjwa.payment.reconciliation.domain;

/** 같은 불일치를 이어 보는 키. 규칙과 함께 쓴다. 대상이 없는 쪽은 null이다. */
public record MismatchKey(Long reservationId, Long paymentId) {}
