package com.iseonjwa.payment.payment.application;

/**
 * 결제 토큰의 서명·용도·대상과 필수 claim을 검증하는 포트. 만료는 검증하지 않고 {@link PaymentToken}에 담아 돌려준다.
 */
public interface PaymentTokenVerifier {
    /**
     * @throws com.iseonjwa.common.response.BusinessException 형식·서명·용도·대상·claim이 올바르지 않으면
     *         {@link PaymentErrorCode#PAYMENT_TOKEN_INVALID}
     */
    PaymentToken verify(String token);
}
