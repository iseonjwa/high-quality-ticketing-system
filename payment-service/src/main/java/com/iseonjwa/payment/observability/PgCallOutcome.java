package com.iseonjwa.payment.observability;

import com.iseonjwa.payment.pg.application.PgClient;

/** PG 호출 결과의 종류. {@code payment.pg.call} 타이머의 {@code outcome} 라벨 값이다. */
public enum PgCallOutcome {
    APPROVED,
    DECLINED,
    REFUNDED,
    REFUND_REJECTED,
    NOT_FOUND,
    PREVENTED,
    ALREADY_APPROVED,
    BILLING_KEY_ISSUED,
    BILLING_KEY_REJECTED,
    UNKNOWN,
    /** 어댑터가 결과 타입 대신 예외를 던졌다 */
    EXCEPTION;

    /** 결과 타입을 라벨 값으로 바꾼다. 포트에 없는 타입이나 null이면 {@link #EXCEPTION}이 아니라 {@link #UNKNOWN}이다. */
    public static PgCallOutcome of(Object result) {
        return switch (result) {
            case PgClient.Approved approved -> APPROVED;
            case PgClient.Declined declined -> DECLINED;
            case PgClient.Refunded refunded -> REFUNDED;
            case PgClient.RefundRejected rejected -> REFUND_REJECTED;
            case PgClient.NotFound notFound -> NOT_FOUND;
            case PgClient.Prevented prevented -> PREVENTED;
            case PgClient.AlreadyApproved alreadyApproved -> ALREADY_APPROVED;
            case PgClient.BillingKeyIssued issued -> BILLING_KEY_ISSUED;
            case PgClient.BillingKeyRejected rejected -> BILLING_KEY_REJECTED;
            case null, default -> UNKNOWN;
        };
    }
}
