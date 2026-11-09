package com.iseonjwa.payment.payment.presentation;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

public final class PaymentDtos {
    private PaymentDtos() {}

    /**
     * 결제 승인 요청 본문. 토큰 길이 상한(4,096자)은 형식 오류(400)가 아니라 토큰 오류(403)라서 여기서 검증하지 않는다.
     * {@code method}는 CARD만 받는다(BILLING은 M4 자동결제 이벤트 전용).
     * {@code clientPaymentRef}는 구매자가 결제창에서 받은 PG 식별자(토스 paymentKey)다. 토스 모드에서 필수이고 Mock 모드에서는 무시한다.
     */
    public record ApproveRequest(
            @NotBlank String paymentToken,
            @NotNull @Positive @Digits(integer = 15, fraction = 0, message = "소수 없는 정수여야 합니다") BigDecimal amount,
            @NotNull @Pattern(regexp = "CARD", message = "CARD만 지원합니다") String method,
            @Pattern(regexp = "\\S{1,200}", message = "공백 없는 1~200자여야 합니다") String clientPaymentRef) {}

    /** 결제창을 열기 전에 준비 정보를 요청하는 본문. 토큰 길이 상한은 승인 API와 같이 서비스가 403으로 거절한다. */
    public record CheckoutRequest(@NotBlank String paymentToken) {}
}
