package com.iseonjwa.payment.reconciliation.infrastructure;

import com.iseonjwa.payment.reconciliation.domain.ReservationSnapshot;
import com.iseonjwa.payment.reconciliation.domain.ReservationStatus;
import com.iseonjwa.payment.reconciliation.domain.RuleCode;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Objects;
import java.util.Optional;

/** RC-3: 결제 {@code REFUNDED}, 예매 {@code CONFIRMED}이고 예매의 {@code paymentId}가 이 결제. 환불됐는데 확정이 유지된다. */
class RefundedButConfirmedRule extends ReservationPaymentRule {
    RefundedButConfirmedRule(JdbcTemplate jdbc) {
        super(jdbc, RuleCode.RC_3);
    }

    @Override
    protected String paymentCondition() {
        return "p.status = 'REFUNDED'";
    }

    @Override
    protected Optional<String> mismatch(PaymentRow payment, ReservationSnapshot reservation) {
        if (reservation != null && reservation.status() == ReservationStatus.CONFIRMED
                && Objects.equals(reservation.paymentId(), payment.id())) {
            return Optional.of("결제는 환불됐는데 예매가 확정(CONFIRMED)으로 남아 있다");
        }
        return Optional.empty();
    }
}
