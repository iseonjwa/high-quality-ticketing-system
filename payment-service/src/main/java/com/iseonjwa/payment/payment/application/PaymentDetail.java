package com.iseonjwa.payment.payment.application;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.iseonjwa.payment.payment.domain.Payment;
import com.iseonjwa.payment.payment.domain.PaymentStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * 결제 조회 응답 {@code data}(api.md 2절). 승인 응답({@link PaymentView})과 달리 상태와 무관하게 같은 여섯 필드를
 * 내보내고(전역 Jackson 설정에 기대지 않도록 null도 명시한다) 해당하지 않는 시각은 null이다. 예매 확정 여부는
 * 담지 않는다(승인은 예매 확정이 아니다).
 *
 * <p>Note: {@code approvedAt}은 승인된 결제(APPROVED, 이후 REFUNDED)만 내보낸다. 금액 불일치로 수동 확인이 된 결제는
 * PG 승인 시각을 저장해 두지만 우리가 승인한 결제가 아니므로 응답에서는 null이다.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record PaymentDetail(Long paymentId, Long reservationId, PaymentStatus status, BigDecimal amount,
        Instant approvedAt, Instant refundedAt) {
    public static PaymentDetail of(Payment payment) {
        var approved = payment.getStatus() == PaymentStatus.APPROVED || payment.getStatus() == PaymentStatus.REFUNDED;
        return new PaymentDetail(payment.getId(), payment.getReservationId(), payment.getStatus(), payment.getAmount(),
                approved ? millis(payment.getApprovedAt()) : null, millis(payment.getRefundedAt()));
    }

    private static Instant millis(Instant instant) {
        return instant == null ? null : instant.truncatedTo(ChronoUnit.MILLIS);
    }
}
