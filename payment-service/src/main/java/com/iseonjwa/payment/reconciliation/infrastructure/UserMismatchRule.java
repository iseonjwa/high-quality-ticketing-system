package com.iseonjwa.payment.reconciliation.infrastructure;

import com.iseonjwa.payment.reconciliation.domain.ReservationSnapshot;
import com.iseonjwa.payment.reconciliation.domain.RuleCode;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Optional;

/** RC-6: {@code APPROVED}·{@code REFUNDED} 결제의 {@code user_id} ≠ 예매 {@code userId}. */
class UserMismatchRule extends ReservationPaymentRule {
    UserMismatchRule(JdbcTemplate jdbc) {
        super(jdbc, RuleCode.RC_6);
    }

    @Override
    protected String paymentCondition() {
        return "p.status IN ('APPROVED', 'REFUNDED')";
    }

    @Override
    protected Optional<String> mismatch(PaymentRow payment, ReservationSnapshot reservation) {
        if (reservation != null && payment.userId() != reservation.userId()) {
            return Optional.of("결제 사용자와 예매 사용자가 다르다");
        }
        return Optional.empty();
    }
}
