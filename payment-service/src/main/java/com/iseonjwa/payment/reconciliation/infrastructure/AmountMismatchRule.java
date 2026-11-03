package com.iseonjwa.payment.reconciliation.infrastructure;

import com.iseonjwa.payment.reconciliation.domain.ReservationSnapshot;
import com.iseonjwa.payment.reconciliation.domain.RuleCode;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * RC-5: {@code APPROVED} 결제의 {@code amount} ≠ 예매 {@code totalAmount}, 또는 {@code REFUNDED} 결제의 환불 금액 ≠
 * {@code pg_approved_amount}. 환불 금액 비교는 예매가 없어도 한다.
 *
 * <p>Note: 예매 금액 비교는 {@code APPROVED}만 본다. 양쪽 금액은 바뀌지 않아서 {@code REFUNDED}까지 보면 한 번 잡힌 불일치가
 * 환불로 처리해도 닫히지 않고(닫는 명령이 없다) 알림이 계속 울린다. {@code APPROVED}만 보면 확인 뒤 환불할 때 다음 대사에서
 * {@code RESOLVED}가 된다. 환불 금액 비교는 환불이 끝난 뒤에야 생기는 불일치라 {@code REFUNDED}를 본다(사람이 환불 행을 고쳐야 닫힌다).
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
        if (reservation != null && "APPROVED".equals(payment.status()) && payment.amount().compareTo(BigDecimal.valueOf(reservation.totalAmount())) != 0) {
            return Optional.of("결제 금액과 예매 금액이 다르다");
        }
        if ("REFUNDED".equals(payment.status()) && payment.refundAmount() != null && payment.pgApprovedAmount() != null
                && payment.refundAmount().compareTo(payment.pgApprovedAmount()) != 0) {
            return Optional.of("환불 금액과 PG 승인 금액이 다르다");
        }
        return Optional.empty();
    }
}
