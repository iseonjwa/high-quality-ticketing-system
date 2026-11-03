package com.iseonjwa.payment.reconciliation.infrastructure;

import com.iseonjwa.payment.reconciliation.domain.ReservationSnapshot;
import com.iseonjwa.payment.reconciliation.domain.RuleCode;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * RC-5: {@code APPROVED}·{@code REFUNDED} 결제의 {@code amount} ≠ 예매 {@code totalAmount}, 또는 {@code REFUNDED} 결제의 환불
 * 금액 ≠ {@code pg_approved_amount}. 환불 금액 비교는 예매가 없어도 한다.
 */
class AmountMismatchRule extends ReservationPaymentRule {
    AmountMismatchRule(JdbcTemplate jdbc) {
        super(jdbc, RuleCode.RC_5);
    }

    @Override
    protected String paymentCondition() {
        return "p.status IN ('APPROVED', 'REFUNDED')";
    }

    @Override
    protected Optional<String> mismatch(PaymentRow payment, ReservationSnapshot reservation) {
        if (reservation != null && payment.amount().compareTo(BigDecimal.valueOf(reservation.totalAmount())) != 0) {
            return Optional.of("결제 금액과 예매 금액이 다르다");
        }
        if ("REFUNDED".equals(payment.status()) && payment.refundAmount() != null && payment.pgApprovedAmount() != null
                && payment.refundAmount().compareTo(payment.pgApprovedAmount()) != 0) {
            return Optional.of("환불 금액과 PG 승인 금액이 다르다");
        }
        return Optional.empty();
    }
}
