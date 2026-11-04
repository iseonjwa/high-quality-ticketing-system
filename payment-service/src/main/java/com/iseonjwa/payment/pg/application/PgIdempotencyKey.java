package com.iseonjwa.payment.pg.application;

/**
 * PG 호출 한 건의 재시도 식별자. 저장하지 않고 우리 식별자에서 결정적으로 만든다.
 * 서버가 죽었다가 같은 호출을 다시 보내도 같은 키가 되어 PG가 같은 호출로 알아본다.
 */
public record PgIdempotencyKey(String value) {
    public PgIdempotencyKey {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("멱등키가 비어 있습니다");
        }
    }

    public static PgIdempotencyKey ofApproval(PgOrderId orderId) {
        return new PgIdempotencyKey(orderId.value().toString());
    }

    public static PgIdempotencyKey ofRefund(PgRefundId refundId) {
        return new PgIdempotencyKey(refundId.value().toString());
    }
}
