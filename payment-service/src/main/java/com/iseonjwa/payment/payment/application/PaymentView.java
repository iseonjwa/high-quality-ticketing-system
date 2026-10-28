package com.iseonjwa.payment.payment.application;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.iseonjwa.payment.payment.domain.Payment;
import com.iseonjwa.payment.payment.domain.PaymentStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * 결제 상태별 응답 {@code data}(api.md 1절 표 A). 상태에 해당하지 않는 필드는 내보내지 않는다.
 *
 * <p>Note: 이 모양은 멱등 기록의 {@code response_body}에 24시간 저장돼 같은 키 재요청에 재생된다. 필드를 지우거나
 * 이름을 바꾸면 저장된 응답을 읽지 못하므로 추가만 한다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PaymentView(Long paymentId, Long reservationId, PaymentStatus status, BigDecimal amount,
        Instant approvedAt, Instant refundedAt, String reasonCode, Integer nextPollAfterSeconds) {
    // 클라이언트가 확인 중인 결제를 다시 조회할 간격(재확인 스케줄러의 첫 주기와 같다)
    static final int NEXT_POLL_AFTER_SECONDS = 10;

    public static PaymentView of(Payment payment) {
        return switch (payment.getStatus()) {
            case REQUESTED, UNKNOWN, NEEDS_MANUAL_CHECK -> new PaymentView(payment.getId(), payment.getReservationId(),
                    payment.getStatus(), null, null, null, null, NEXT_POLL_AFTER_SECONDS);
            case APPROVED -> new PaymentView(payment.getId(), payment.getReservationId(), payment.getStatus(),
                    payment.getAmount(), millis(payment.getApprovedAt()), null, null, null);
            case DECLINED -> new PaymentView(payment.getId(), payment.getReservationId(), payment.getStatus(), null,
                    null, null, payment.getDeclineCode().name(), null);
            case REFUNDED -> new PaymentView(payment.getId(), payment.getReservationId(), payment.getStatus(),
                    payment.getAmount(), null, millis(payment.getRefundedAt()), null, null);
        };
    }

    private static Instant millis(Instant instant) {
        return instant == null ? null : instant.truncatedTo(ChronoUnit.MILLIS);
    }
}
