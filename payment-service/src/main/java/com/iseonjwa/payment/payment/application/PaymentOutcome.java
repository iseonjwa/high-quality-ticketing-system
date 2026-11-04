package com.iseonjwa.payment.payment.application;

import com.iseonjwa.common.response.ErrorCode;
import com.iseonjwa.payment.payment.domain.Payment;

/**
 * 승인 요청의 처리 결과. 성공(200·202)은 {@code data}를, 업무 거절(409 등)은 {@code error}를 가진다.
 * {@code locationPaymentId}가 있으면 {@code Location: /api/v1/payments/{id}} 헤더를 붙인다.
 */
public record PaymentOutcome(int httpStatus, PaymentView data, ErrorCode error, Long locationPaymentId) {
    // 결제의 현재 상태로 응답한다(api.md 표 A). 확인 필요(NEEDS_MANUAL_CHECK)는 409 + Location이다
    public static PaymentOutcome of(Payment payment) {
        var view = PaymentView.of(payment);
        return switch (payment.getStatus()) {
            case REQUESTED, UNKNOWN -> new PaymentOutcome(202, view, null, payment.getId());
            case APPROVED, DECLINED, REFUNDED -> new PaymentOutcome(200, view, null, null);
            case NEEDS_MANUAL_CHECK -> new PaymentOutcome(PaymentErrorCode.PAYMENT_ALREADY_EXISTS.status(), null,
                    PaymentErrorCode.PAYMENT_ALREADY_EXISTS, payment.getId());
        };
    }

    // 저장해 둔 응답을 재생한다. 202면 Location을 다시 붙인다
    public static PaymentOutcome replay(int httpStatus, PaymentView data) {
        return new PaymentOutcome(httpStatus, data, null, httpStatus == 202 ? data.paymentId() : null);
    }

    public static PaymentOutcome rejected(ErrorCode error) {
        return new PaymentOutcome(error.status(), null, error, null);
    }

    public boolean isError() {
        return error != null;
    }
}
