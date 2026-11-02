package com.iseonjwa.payment.refund.application;

import java.math.BigDecimal;

/**
 * {@code RefundRequested} 이벤트의 페이로드(0027). 봉투를 파싱한 값이고, 결제와 맞는지는 {@link RefundRequestService}가 대조한다.
 */
public record RefundRequestedPayload(Long reservationId, Long paymentId, Long userId, BigDecimal amount,
                                     String currency, String reason) {}
