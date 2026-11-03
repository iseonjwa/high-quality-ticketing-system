package com.iseonjwa.payment.refund.application;

import java.util.UUID;

/** 봉투 검사를 통과한 {@code RefundRequested}. 중복 판단 기준인 eventId와 페이로드만 남긴다. */
public record RefundRequestedMessage(UUID eventId, RefundRequestedPayload payload) {}
