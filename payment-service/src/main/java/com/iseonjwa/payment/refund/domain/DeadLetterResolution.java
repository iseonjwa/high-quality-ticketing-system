package com.iseonjwa.payment.refund.domain;

/**
 * DLQ 메시지가 닫힌 방식. DLQ 처리기는 {@code AUTO_PERSISTED}·{@code DUPLICATE}만 쓰고,
 * {@code REPLAYED}·{@code NO_OBLIGATION}은 Ops {@code dead-letter resolve}가 쓴다(0028).
 */
public enum DeadLetterResolution {
    AUTO_PERSISTED,
    DUPLICATE,
    REPLAYED,
    NO_OBLIGATION
}
