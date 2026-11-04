package com.iseonjwa.payment.reconciliation.infrastructure;

import com.iseonjwa.payment.reconciliation.domain.ReservationSnapshot;
import com.iseonjwa.payment.reconciliation.domain.RuleCode;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Optional;

/**
 * RC-6: {@code APPROVED} 결제의 {@code user_id} ≠ 예매 {@code userId}.
 *
 * <p>Note: {@code REFUNDED}는 보지 않는다. 사용자는 바뀌지 않아서 환불로 처리해도 불일치가 닫히지 않고 알림이 계속 울리기 때문이다.
 * {@code APPROVED}일 때 확인하고 환불하면 다음 대사에서 {@code RESOLVED}가 된다.
 */
class UserMismatchRule extends ReservationPaymentRule {
    UserMismatchRule(JdbcTemplate jdbc) {
        super(jdbc, RuleCode.RC_6);
    }

    @Override
    protected String paymentCondition() {
        return "p.status = 'APPROVED'";
    }

    @Override
    protected Optional<String> mismatch(PaymentRow payment, ReservationSnapshot reservation) {
        if (reservation != null && payment.userId() != reservation.userId()) {
            return Optional.of("결제 사용자와 예매 사용자가 다르다");
        }
        return Optional.empty();
    }
}
