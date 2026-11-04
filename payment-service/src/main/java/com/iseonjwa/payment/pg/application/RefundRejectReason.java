package com.iseonjwa.payment.pg.application;

public enum RefundRejectReason {
    // 이미 환불됐거나 환불할 수 없는 상태
    NOT_REFUNDABLE,
    // 매핑할 수 없는 PG 사유
    OTHER
}
