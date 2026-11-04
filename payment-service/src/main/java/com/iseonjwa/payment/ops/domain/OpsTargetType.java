package com.iseonjwa.payment.ops.domain;

/** 감사 기록({@code manual_action_logs.target_type})의 대상 종류. */
public enum OpsTargetType {
    PAYMENT, REFUND, OUTBOX, DEAD_LETTER
}
