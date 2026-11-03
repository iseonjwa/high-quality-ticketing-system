package com.iseonjwa.reservation.reservation.application;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * {@code payment.result}에서 읽은 결제 결과 한 건(SA 10-3). 세 이벤트를 한 클래스로 받고, 이벤트에 없는 값은 null이다.
 * 승인: amount·currency·occurredAt(approvedAt). 거절: reasonCode. 환불 완료: amount·currency·occurredAt(refundedAt).
 */
@Getter
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public class PaymentResult {
    public enum Type { APPROVED, DECLINED, REFUNDED }

    private final UUID eventId;
    private final Type type;
    private final Long paymentId;
    private final Long reservationId;
    private final Long userId;
    private final BigDecimal amount;
    private final String currency;
    private final Instant occurredAt;
    private final String reasonCode;

    public static PaymentResult approved(UUID eventId, Long paymentId, Long reservationId, Long userId,
            BigDecimal amount, String currency, Instant approvedAt) {
        return new PaymentResult(eventId, Type.APPROVED, paymentId, reservationId, userId, amount, currency,
                approvedAt, null);
    }

    public static PaymentResult declined(UUID eventId, Long paymentId, Long reservationId, Long userId,
            String reasonCode) {
        return new PaymentResult(eventId, Type.DECLINED, paymentId, reservationId, userId, null, null, null,
                reasonCode);
    }

    public static PaymentResult refunded(UUID eventId, Long paymentId, Long reservationId, Long userId,
            BigDecimal amount, String currency, Instant refundedAt) {
        return new PaymentResult(eventId, Type.REFUNDED, paymentId, reservationId, userId, amount, currency,
                refundedAt, null);
    }
}
