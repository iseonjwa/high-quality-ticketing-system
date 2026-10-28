package com.iseonjwa.payment.payment.infrastructure.token;

/**
 * 결제 토큰의 claim 이름. 토큰 형식은 발급하는 Reservation 담당이 정한다(SA 17-1 U1).
 *
 * <p>TODO: 아직 통보받지 못해 SA 7-3의 서술에서 정한 가정 값이다. 통보된 이름이 다르면 이 파일만 고친다.
 * 가정: {@code sub}=사용자 ID, {@code reservationId}=예매 ID, {@code amount}=원화 정수 금액, {@code typ}=payment,
 * {@code aud}=payment-service, {@code jti}·{@code iat}·{@code exp}는 표준 claim이다.
 */
final class PaymentTokenClaims {
    static final String TYPE = "typ";
    static final String TYPE_VALUE = "payment";
    static final String AUDIENCE_VALUE = "payment-service";
    static final String RESERVATION_ID = "reservationId";
    static final String AMOUNT = "amount";

    private PaymentTokenClaims() {}
}
