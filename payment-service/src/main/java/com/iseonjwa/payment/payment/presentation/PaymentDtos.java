package com.iseonjwa.payment.payment.presentation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

public final class PaymentDtos {
    private PaymentDtos() {}

    /**
     * 결제 승인 요청 본문. 토큰 길이 상한(4,096자)은 형식 오류(400)가 아니라 토큰 오류(403)라서 여기서 검증하지 않는다.
     * {@code method}는 CARD만 받는다(BILLING은 M4 자동결제 이벤트 전용).
     */
    public record ApproveRequest(
            @NotBlank String paymentToken,
            @NotNull @Positive Long amount,
            @NotNull @Pattern(regexp = "CARD", message = "CARD만 지원합니다") String method) {}
}
