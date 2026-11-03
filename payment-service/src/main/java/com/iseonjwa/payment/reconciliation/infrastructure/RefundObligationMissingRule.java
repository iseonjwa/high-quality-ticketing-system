package com.iseonjwa.payment.reconciliation.infrastructure;

import com.iseonjwa.payment.reconciliation.domain.ReservationSnapshot;
import com.iseonjwa.payment.reconciliation.domain.ReservationStatus;
import com.iseonjwa.payment.reconciliation.domain.RuleCode;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Optional;

/**
 * RC-2: 결제 {@code APPROVED}, 환불 행 없음, 예매 {@code EXPIRED}·{@code RELEASED}·{@code CANCELED} 또는 예매 없음. 환불 의무가
 * 등록되지 않았다(가장 심각). 환불 행이 있으면 {@code FAILED}여도 의무를 추적 중이라 이 규칙이 아니다.
 *
 * <p>Note: 예매 없음은 묶음 조회 응답에서 빠진 ID만 뜻한다. 조회 실패(404 포함)는 이 규칙까지 오지 않고 준비 단계에서 실패한다.
 */
class RefundObligationMissingRule extends ReservationPaymentRule {
    RefundObligationMissingRule(JdbcTemplate jdbc) {
        super(jdbc, RuleCode.RC_2);
    }

    @Override
    protected String paymentCondition() {
        return "p.status = 'APPROVED' AND r.id IS NULL";
    }

    @Override
    protected Optional<String> mismatch(PaymentRow payment, ReservationSnapshot reservation) {
        if (reservation == null) return Optional.of("결제는 승인됐는데 예매가 없고 환불 행도 없다");
        if (reservation.status() == ReservationStatus.EXPIRED || reservation.status() == ReservationStatus.RELEASED
                || reservation.status() == ReservationStatus.CANCELED) {
            return Optional.of("결제는 승인됐는데 예매가 " + reservation.status() + "이고 환불 행이 없다");
        }
        return Optional.empty();
    }
}
