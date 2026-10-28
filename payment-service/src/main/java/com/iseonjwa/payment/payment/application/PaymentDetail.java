package com.iseonjwa.payment.payment.application;

import com.iseonjwa.payment.payment.domain.Payment;
import com.iseonjwa.payment.payment.domain.PaymentStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * 결제 조회 응답 {@code data}(api.md 2절). 승인 응답({@link PaymentView})과 달리 상태와 무관하게 같은 필드를 내보내고
 * 해당하지 않는 시각은 null이다. 예매 확정 여부는 담지 않는다(승인은 예매 확정이 아니다).
 */
public record PaymentDetail(Long paymentId, Long reservationId, PaymentStatus status, BigDecimal amount,
        Instant approvedAt, Instant refundedAt) {
    public static PaymentDetail of(Payment payment) {
        return new PaymentDetail(payment.getId(), payment.getReservationId(), payment.getStatus(), payment.getAmount(),
                millis(payment.getApprovedAt()), millis(payment.getRefundedAt()));
    }

    private static Instant millis(Instant instant) {
        return instant == null ? null : instant.truncatedTo(ChronoUnit.MILLIS);
    }
}
