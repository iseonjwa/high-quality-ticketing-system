package com.iseonjwa.payment.payment.application;

import com.iseonjwa.common.response.BusinessException;
import com.iseonjwa.payment.pg.application.PgCheckoutProfile;
import com.iseonjwa.payment.pg.application.PgOrderIds;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.Clock;

/**
 * 결제창을 열기 전에 클라이언트가 필요한 값을 돌려준다(api.md 1-1절). 토스는 결제창에 {@code orderId}와 금액과
 * 클라이언트 키가 필요하고, Mock은 결제창이 없어 금액과 주문명만 돌려준다.
 *
 * <p>Note: 저장하지 않는다. {@code orderId}는 결제 토큰에서 결정적으로 만들어 TX1이 같은 값을 얻는다({@link PgOrderIds}).
 * 새 결제를 만들기 위한 호출이라 만료된 토큰은 받지 않는다(승인 API의 조회 전용 유예와 다르다).
 */
@Service
public class PaymentCheckoutService {
    private final PaymentTokenVerifier tokens;
    private final PgCheckoutProfile profile;
    private final Clock clock;

    public PaymentCheckoutService(PaymentTokenVerifier tokens, PgCheckoutProfile profile,
            @Qualifier("commonAuditingClock") Clock clock) {
        this.tokens = tokens;
        this.profile = profile;
        this.clock = clock;
    }

    public CheckoutView checkout(long userId, String paymentToken) {
        if (paymentToken.length() > PaymentTokenVerifier.MAX_TOKEN_LENGTH) {
            throw new BusinessException(PaymentErrorCode.PAYMENT_TOKEN_INVALID);
        }
        var token = tokens.verify(paymentToken);
        // 토큰은 다른 사용자의 것이어도 서명은 맞을 수 있다. 인증된 사용자와 같아야 한다
        if (token.userId() != userId || token.isExpired(clock.instant())) {
            throw new BusinessException(PaymentErrorCode.PAYMENT_TOKEN_INVALID);
        }
        var orderName = "콘서트 예매 " + token.reservationId();
        // 건너뛰기는 토스 모드에서만 의미가 있다. Mock 모드는 결제 버튼이 이미 바로 승인한다
        return switch (profile.provider()) {
            case MOCK -> new CheckoutView(profile.provider(), false, null, null, token.amount(), orderName);
            case TOSS -> new CheckoutView(profile.provider(), profile.mockBypassEnabled(),
                    PgOrderIds.of(token.reservationId(), token.expiresAt()).value().toString(), profile.clientKey(),
                    token.amount(), orderName);
        };
    }
}
