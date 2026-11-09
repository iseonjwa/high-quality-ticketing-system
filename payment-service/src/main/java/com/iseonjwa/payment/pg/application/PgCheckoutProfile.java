package com.iseonjwa.payment.pg.application;

import java.util.Objects;

/**
 * 결제 서비스가 PG 모드를 알아야 하는 곳(승인 API의 clientPaymentRef 검사, checkout API)에 주는 읽기 전용 정보다.
 * 설정 객체와 PG 키를 서비스 코드에 노출하지 않으려고 따로 둔다.
 *
 * @param clientKey 토스 결제창이 쓰는 클라이언트 키. Mock이면 null이다.
 */
public record PgCheckoutProfile(Provider provider, String clientKey) {
    public enum Provider { MOCK, TOSS }

    public PgCheckoutProfile {
        Objects.requireNonNull(provider);
    }

    // 토스는 구매자가 결제창에서 받은 paymentKey 없이는 승인할 수 없다
    public boolean requiresClientPaymentRef() {
        return provider == Provider.TOSS;
    }

    @Override
    public String toString() {
        return "PgCheckoutProfile[provider=" + provider + "]";
    }
}
