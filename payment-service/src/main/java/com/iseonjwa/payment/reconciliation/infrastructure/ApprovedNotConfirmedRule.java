package com.iseonjwa.payment.reconciliation.infrastructure;

import com.iseonjwa.payment.reconciliation.domain.ReservationSnapshot;
import com.iseonjwa.payment.reconciliation.domain.ReservationStatus;
import com.iseonjwa.payment.reconciliation.domain.RuleCode;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Optional;

/** RC-1: 결제 {@code APPROVED}, 환불 행 없음, 예매 {@code HELD}. 승인됐는데 확정되지 않았다. */
class ApprovedNotConfirmedRule extends ReservationPaymentRule {
    ApprovedNotConfirmedRule(JdbcTemplate jdbc) {
        super(jdbc, RuleCode.RC_1);
    }

    @Override
    protected String paymentCondition() {
        return "p.status = 'APPROVED' AND r.id IS NULL";
    }

    @Override
    protected Optional<String> mismatch(PaymentRow payment, ReservationSnapshot reservation) {
        if (reservation != null && reservation.status() == ReservationStatus.HELD) {
            return Optional.of("결제는 승인됐는데 예매가 선점(HELD) 상태다");
        }
        return Optional.empty();
    }
}
